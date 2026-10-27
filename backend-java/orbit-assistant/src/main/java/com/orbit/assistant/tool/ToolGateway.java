package com.orbit.assistant.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orbit.common.error.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 只通过既有 HTTP 契约访问 academic/scheduling，不跨服务写库。 */
@Component
public class ToolGateway {

    public static final List<String> READ_TOOLS = List.of(
            "check_teacher_availability", "check_class_availability", "find_free_slots",
            "get_timetable", "get_job_status", "explain_diagnosis", "score_timetable", "check_config");
    public static final List<String> PROPOSAL_TOOLS = List.of(
            "propose_rule", "propose_swap", "propose_reschedule");

    private final RestClient academic;
    private final RestClient scheduling;
    private final ObjectMapper json;

    public ToolGateway(RestClient.Builder builder, ObjectMapper json,
                       @Value("${orbit.academic.base-url}") String academicUrl,
                       @Value("${orbit.scheduling.base-url}") String schedulingUrl) {
        this.academic = builder.clone().baseUrl(academicUrl).build();
        this.scheduling = builder.clone().baseUrl(schedulingUrl).build();
        this.json = json;
    }

    public Map<String, Object> executeRead(String tool, Map<String, Object> args, String authorization) {
        return switch (tool) {
            case "check_teacher_availability" -> getScheduling("/api/availability/teacher/"
                    + resolveId(args, "teacher_id", "teacher_name", "/api/teachers", authorization), args, authorization,
                    List.of("version_id", "day", "period"));
            case "check_class_availability" -> getScheduling("/api/availability/class/"
                    + resolveId(args, "class_id", "class_name", "/api/classes", authorization), args, authorization,
                    List.of("version_id", "day", "period"));
            case "find_free_slots" -> getScheduling("/api/availability/teacher/"
                    + resolveId(args, "teacher_id", "teacher_name", "/api/teachers", authorization)
                    + "/free-slots", args, authorization,
                    List.of("version_id", "day"));
            case "get_timetable" -> timetable(args, authorization);
            case "get_job_status" -> getScheduling("/api/schedule/jobs/"
                    + requiredLong(args, "job_id"), Map.of(), authorization, List.of());
            case "explain_diagnosis" -> getScheduling("/api/schedule/diagnosis", args,
                    authorization, List.of("version_id", "deep"));
            case "score_timetable" -> getScheduling("/api/schedule/versions/"
                    + requiredLong(args, "version_id") + "/score", Map.of(), authorization, List.of());
            case "check_config" -> Map.of("data", checkConfig(authorization));
            default -> throw BusinessException.badRequest("未知的助手工具: " + tool);
        };
    }

    public Proposal prepareProposal(String tool, Map<String, Object> args, String authorization) {
        return switch (tool) {
            case "propose_rule" -> {
                Map<String, Object> request = resolveRuleNames(nestedOrSelf(args, "request"), authorization);
                Map<String, Object> trial = postScheduling("/api/rules/trial", request, authorization);
                yield new Proposal(Map.of("request", request, "preview", trial), null);
            }
            case "propose_swap" -> {
                long versionId = requiredLong(args, "version_id");
                Map<String, Object> request = new LinkedHashMap<>(nestedOrSelf(args, "request"));
                if (!request.containsKey("target_class_id") && request.containsKey("target_class_name")) {
                    request.put("target_class_id", resolveId(request, "target_class_id", "target_class_name",
                            "/api/classes", authorization));
                    request.remove("target_class_name");
                }
                Map<String, Object> preview = postScheduling(
                        "/api/schedule/versions/" + versionId + "/swap/preview", request, authorization);
                Integer revision = integer(request.get("expected_revision"));
                yield new Proposal(Map.of("version_id", versionId, "request", request, "preview", preview), revision);
            }
            case "propose_reschedule" -> {
                Map<String, Object> config = checkConfig(authorization);
                yield new Proposal(Map.of("request", Map.of(), "config", config,
                        "warning", "确认后会创建新的排课任务，不会直接发布课表"), null);
            }
            default -> throw BusinessException.badRequest("未知的草案工具: " + tool);
        };
    }

    public Map<String, Object> apply(String proposalTool, Map<String, Object> proposal,
                                     String authorization) {
        return switch (proposalTool) {
            case "propose_rule" -> postScheduling("/api/rules",
                    map(proposal.get("request")), authorization);
            case "propose_swap" -> postScheduling("/api/schedule/versions/"
                    + requiredLong(proposal, "version_id") + "/swap",
                    map(proposal.get("request")), authorization);
            case "propose_reschedule" -> postScheduling("/api/schedule/generate", Map.of(), authorization);
            default -> throw BusinessException.badRequest("该动作不是可确认草案");
        };
    }

    private Map<String, Object> timetable(Map<String, Object> args, String authorization) {
        String type = String.valueOf(args.getOrDefault("resource_type", "class"));
        if (!List.of("class", "teacher", "grade").contains(type)) {
            throw BusinessException.badRequest("resource_type 必须是 class、teacher 或 grade");
        }
        long resourceId = resolveId(args, "resource_id", "resource_name", "/api/" + switch (type) {
            case "class" -> "classes";
            case "teacher" -> "teachers";
            default -> "grades";
        }, authorization);
        return getScheduling("/api/schedule/" + type + "/" + resourceId,
                args, authorization, List.of("version_id"));
    }

    private Map<String, Object> checkConfig(String authorization) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("grades", getAcademicAll("/api/grades", authorization));
        result.put("classes", getAcademicAll("/api/classes", authorization));
        result.put("subjects", getAcademicAll("/api/subjects", authorization));
        result.put("teachers", getAcademicAll("/api/teachers", authorization));
        result.put("assignments", getAcademicAll("/api/assignments", authorization));
        result.put("grade_subjects", getAcademicAll("/api/grade-subjects", authorization));
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getAcademicAll(String path, String authorization) {
        List<Object> items = new ArrayList<>();
        long total = 0;
        for (int page = 1; page <= 100; page++) {
            Map<String, Object> response = getAcademicPage(path, page, 200, null, authorization);
            Object data = response.get("data");
            if (!(data instanceof Map<?, ?> body)) return response;
            Object rawItems = body.get("items");
            List<Object> pageItems = rawItems instanceof List<?> found ? (List<Object>) found : List.of();
            items.addAll(pageItems);
            total = body.get("total") instanceof Number number ? number.longValue() : items.size();
            if (items.size() >= total || pageItems.isEmpty()) break;
        }
        return Map.of("items", items, "total", total);
    }

    private Map<String, Object> getAcademicPage(String path, int page, int size, String keyword,
                                                String authorization) {
        UriComponentsBuilder uri = UriComponentsBuilder.fromPath(path)
                .queryParam("page", page).queryParam("size", size);
        if (keyword != null && !keyword.isBlank()) uri.queryParam("keyword", keyword);
        return request(academic.get().uri(uri.build().encode().toUriString())
                .header(HttpHeaders.AUTHORIZATION, authorization));
    }

    private Map<String, Object> getScheduling(String path, Map<String, Object> args,
                                              String authorization, List<String> queryNames) {
        UriComponentsBuilder uri = UriComponentsBuilder.fromPath(path);
        for (String name : queryNames) {
            Object value = args.get(name);
            if (value != null) uri.queryParam(name, value);
        }
        return request(scheduling.get().uri(uri.build().toUriString())
                .header(HttpHeaders.AUTHORIZATION, authorization));
    }

    private Map<String, Object> postScheduling(String path, Map<String, Object> body,
                                               String authorization) {
        return request(scheduling.post().uri(path).header(HttpHeaders.AUTHORIZATION, authorization).body(body));
    }

    private Map<String, Object> request(RestClient.RequestHeadersSpec<?> request) {
        try {
            // Boot 4 的 RestClient 使用 Jackson 3；读成标准 Object 可得到 Map/List，
            // 不要要求消息转换器直接构造本模块用于审计的 Jackson 2 JsonNode。
            Object node = request.retrieve().body(Object.class);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("data", node);
            return out;
        } catch (RestClientResponseException error) {
            String detail = error.getStatusText();
            try {
                JsonNode body = json.readTree(error.getResponseBodyAsString());
                if (body.hasNonNull("detail")) detail = body.get("detail").asText();
            } catch (Exception ignored) {
                // 保留 HTTP status text
            }
            throw new BusinessException(error.getStatusCode().value(), detail);
        }
    }

    private static long requiredLong(Map<String, Object> args, String name) {
        Object value = args.get(name);
        if (value instanceof Number number) return number.longValue();
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (Exception error) {
            throw BusinessException.badRequest("助手工具缺少整数参数 " + name);
        }
    }

    @SuppressWarnings("unchecked")
    private long resolveId(Map<String, Object> args, String idKey, String nameKey,
                           String path, String authorization) {
        if (args.get(idKey) != null) return requiredLong(args, idKey);
        String name = String.valueOf(args.getOrDefault(nameKey, "")).trim();
        if (name.isBlank()) throw BusinessException.badRequest("助手工具缺少参数 " + idKey + " 或 " + nameKey);
        Map<String, Object> response = getAcademicPage(path, 1, 200, name, authorization);
        Object data = response.get("data");
        List<Object> items = data instanceof Map<?, ?> body && body.get("items") instanceof List<?> found
                ? (List<Object>) found : List.of();
        List<Map<String, Object>> exact = items.stream()
                .filter(Map.class::isInstance)
                .map(value -> (Map<String, Object>) value)
                .filter(value -> name.equals(String.valueOf(value.get("name"))))
                .toList();
        if (exact.isEmpty()) throw BusinessException.badRequest("没有找到名称为“" + name + "”的对象");
        if (exact.size() > 1) throw BusinessException.badRequest("名称“" + name + "”不唯一，请改用 ID");
        return requiredLong(exact.getFirst(), "id");
    }

    private Map<String, Object> resolveRuleNames(Map<String, Object> request, String authorization) {
        Map<String, Object> resolved = new LinkedHashMap<>(request);
        Map<String, Object> params = new LinkedHashMap<>(map(request.get("params")));
        if (!params.containsKey("teacher_id") && params.containsKey("teacher_name")) {
            params.put("teacher_id", resolveId(params, "teacher_id", "teacher_name", "/api/teachers", authorization));
            params.remove("teacher_name");
        }
        if (!params.containsKey("subject_id") && params.containsKey("subject_name")) {
            params.put("subject_id", resolveId(params, "subject_id", "subject_name", "/api/subjects", authorization));
            params.remove("subject_name");
        }
        if (!params.containsKey("class_id") && params.containsKey("class_name")) {
            params.put("class_id", resolveId(params, "class_id", "class_name", "/api/classes", authorization));
            params.remove("class_name");
        }
        resolved.put("params", params);
        return resolved;
    }

    private static Integer integer(Object value) {
        if (value == null) return null;
        if (value instanceof Number number) return number.intValue();
        return Integer.valueOf(String.valueOf(value));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> found ? (Map<String, Object>) found : Map.of();
    }

    private static Map<String, Object> nestedOrSelf(Map<String, Object> args, String key) {
        Map<String, Object> nested = map(args.get(key));
        if (!nested.isEmpty()) return nested;
        Map<String, Object> copy = new LinkedHashMap<>(args);
        copy.remove("version_id");
        return copy;
    }

    public record Proposal(Map<String, Object> body, Integer expectedRevision) {
    }
}
