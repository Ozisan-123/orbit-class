package com.orbit.assistant.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orbit.assistant.tool.IntentRouter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** 可选的模型意图路由。模型只选择工具和参数，业务事实仍由内部服务返回。 */
@Component
public class LlmIntentRouter {
    private static final Set<String> ALLOWED_TOOLS = Set.of(
            "check_teacher_availability", "check_class_availability", "find_free_slots",
            "get_timetable", "get_job_status", "explain_diagnosis", "score_timetable",
            "check_config", "propose_rule", "propose_swap", "propose_reschedule"
    );

    private static final String INSTRUCTIONS = """
            你是智能教务中台的意图路由器。你不能直接修改数据，只能选择一个工具并提取参数。
            只输出 JSON，不要输出 Markdown：{"tool":"工具名","arguments":{...}}
            信息不足时输出：{"tool":null,"arguments":{},"question":"需要追问的问题"}

            工具：
            check_teacher_availability: teacher_id或teacher_name, version_id, day, period
            check_class_availability: class_id或class_name, version_id, day, period
            find_free_slots: teacher_id或teacher_name, version_id, day
            get_timetable: resource_type(class/teacher/grade), resource_id或resource_name, 可选version_id
            get_job_status: job_id
            explain_diagnosis: 可选version_id和deep
            score_timetable: version_id
            check_config: 无参数
            propose_rule: request对象，包含rule_type、params、hardness、weight、scope、enabled、description
            propose_swap: version_id和request对象，request包含source_entry_id、target_class_id、target_day_of_week、target_period_index、expected_revision
            propose_reschedule: 无参数

            day 和 target_day_of_week 使用 0-4（周一为0）；period 和 target_period_index 使用从0开始的节次。
            用户说“第2节”时应输出1。不得编造 ID、姓名、版本号或修订号。
            所有写操作只能选择 propose_*；不得声称已经执行。名称可以原样保留给后端做唯一性校验。
            问候或无关问题返回 tool=null，并简短说明可处理的教务问题。
            """;

    private final ObjectMapper objectMapper;
    private volatile HttpClient httpClient;
    private final String apiKey;
    private final String baseUrl;
    private final String model;

    public LlmIntentRouter(ObjectMapper objectMapper,
                           @Value("${orbit.assistant.llm-api-key:}") String apiKey,
                           @Value("${orbit.assistant.llm-base-url:https://api.openai.com}") String baseUrl,
                           @Value("${orbit.assistant.llm-model:gpt-4o-mini}") String model) {
        this.objectMapper = objectMapper;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.baseUrl = baseUrl == null ? "https://api.openai.com" : baseUrl.trim();
        this.model = model;
    }

    public boolean enabled() {
        return !apiKey.isBlank();
    }

    public IntentRouter.Routed route(String message) {
        try {
            Map<String, Object> body = Map.of(
                    "model", model,
                    "instructions", INSTRUCTIONS,
                    "input", message
            );
            HttpRequest request = HttpRequest.newBuilder(resolveEndpoint())
                    .timeout(Duration.ofSeconds(25))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> response = httpClient().send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("模型服务返回 HTTP " + response.statusCode());
            }
            return parseRoute(response.body());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("模型路由被中断", error);
        } catch (ClarificationRequiredException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalStateException("模型路由失败", error);
        }
    }

    @SuppressWarnings("unchecked")
    IntentRouter.Routed parseRoute(String responseBody) throws Exception {
        JsonNode root = objectMapper.readTree(responseBody);
        String output = root.path("output_text").asText("");
        if (output.isBlank()) {
            outer:
            for (JsonNode item : root.path("output")) {
                for (JsonNode content : item.path("content")) {
                    if ("output_text".equals(content.path("type").asText())) {
                        output = content.path("text").asText("");
                        if (!output.isBlank()) break outer;
                    }
                }
            }
        }
        JsonNode decision = objectMapper.readTree(stripCodeFence(output));
        JsonNode toolNode = decision.path("tool");
        if (toolNode.isMissingNode() || toolNode.isNull() || toolNode.asText().isBlank()) {
            throw new ClarificationRequiredException(
                    decision.path("question").asText("请补充要查询的对象、时间或相关编号。"));
        }
        String tool = toolNode.asText();
        if (!ALLOWED_TOOLS.contains(tool)) {
            throw new IllegalStateException("模型选择了未授权工具");
        }
        Map<String, Object> arguments = decision.path("arguments").isObject()
                ? objectMapper.convertValue(decision.path("arguments"), Map.class)
                : new HashMap<>();
        return new IntentRouter.Routed(tool, arguments);
    }

    private URI resolveEndpoint() {
        String normalized = baseUrl.replaceAll("/+$", "");
        if (normalized.endsWith("/responses")) return URI.create(normalized);
        if (normalized.endsWith("/v1")) return URI.create(normalized + "/responses");
        return URI.create(normalized + "/v1/responses");
    }

    private HttpClient httpClient() {
        HttpClient current = httpClient;
        if (current != null) return current;
        synchronized (this) {
            if (httpClient == null) {
                httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
            }
            return httpClient;
        }
    }

    private static String stripCodeFence(String value) {
        String text = value == null ? "" : value.trim();
        if (text.startsWith("```")) {
            text = text.replaceFirst("^```(?:json)?\\s*", "");
            text = text.replaceFirst("\\s*```$", "");
        }
        return text.trim();
    }

    public static class ClarificationRequiredException extends RuntimeException {
        public ClarificationRequiredException(String message) {
            super(message);
        }
    }
}
