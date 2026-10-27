package com.orbit.solver.worker;

import com.orbit.solver.core.model.TimetableEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;
import java.util.Map;

/**
 * 访问 scheduling 内部接口的客户端（P3）。
 *
 * <h2>为什么 worker 不直连数据库</h2>
 * 方案 §2.2 的服务清单写明 solver-worker「拥有的表：**无**」。
 * 而 `SKIP LOCKED` 领单本质是**写** `schedule_jobs`，直连就违反铁律 2。
 * 所以走这三个内部接口 —— 表属主始终清晰，P3 之后拆独立库不用回头改。
 *
 * <h2>三个接口的语义（worker 必须区分）</h2>
 * <ul>
 *   <li>{@code claim} → 200 带任务 / **204 没活干**（正常状态，不是错误）</li>
 *   <li>{@code heartbeat} → {@code ok=false} 表示 token 已失效（被接管），**立刻停手**；
 *       {@code cancel_requested=true} 表示用户点了取消</li>
 *   <li>{@code result} → {@code accepted=false} 表示**结果被丢弃**（多半是被接管了）。
 *       这不是错误，不必重试，也不该报错</li>
 * </ul>
 */
@Component
public class SchedulingClient {

    private static final Logger log = LoggerFactory.getLogger(SchedulingClient.class);

    private final RestClient http;
    private final WorkerProperties properties;

    /**
     * ⚠ 必须注入 **Boot 配置好的** {@code RestClient.Builder}，不能用静态工厂
     * {@code RestClient.builder()}。
     *
     * <p>两者差别不在"能不能发请求"，而在**消息转换器里的 ObjectMapper**：
     * 静态工厂造出来的是裸的，不带 {@code spring.jackson.*} 的配置，
     * 于是 snake_case 的响应匹配不上 camelCase 的 record 字段 →
     * 字段全是 null → primitive 字段直接抛
     * {@code Cannot map 'null' into type 'long'}。
     *
     * <p>服务能启动、能连上、能领到任务，**只是读不懂响应** —— 这类问题最难查。
     * 而它依赖的 `spring-boot-restclient` 模块，`spring-boot-starter-web` 并不包含
     * （见 pom 里的注释）。
     */
    public SchedulingClient(RestClient.Builder builder, WorkerProperties properties) {
        this.properties = properties;
        this.http = builder
                .baseUrl(properties.getSchedulingBaseUrl())
                .defaultHeader("X-Worker-Secret", properties.getSecret())
                .build();
    }

    /** 领单。没有可领的返回 null（HTTP 204）；scheduling 不可用也返回 null（下一轮再试）。 */
    public ClaimedJob claim() {
        try {
            // 204 No Content → RestClient 返回 null，不需要额外的 onStatus 处理
            return http.post()
                    .uri(uri -> uri.path("/internal/jobs/claim")
                            .queryParam("worker_id", properties.getId()).build())
                    .retrieve()
                    .body(ClaimedJob.class);
        } catch (RestClientResponseException e) {
            log.warn("领单失败: {} {}", e.getStatusCode(), e.getStatusText());
            return null;
        } catch (ResourceAccessException e) {
            // scheduling 暂时不可达（重启中 / 网络抖动）—— 静默等下一轮，别刷屏
            log.debug("scheduling 暂不可达: {}", e.getMessage());
            return null;
        }
    }

    /** 心跳结果：{@code ok=false} = 被接管了，{@code cancelRequested=true} = 用户要取消。 */
    public record HeartbeatResult(boolean ok, boolean cancelRequested) {
    }

    public HeartbeatResult heartbeat(long jobId, String jobToken, int progress) {
        try {
            Map<?, ?> body = http.post()
                    .uri("/internal/jobs/{id}/heartbeat", jobId)
                    .body(Map.of("job_token", jobToken, "progress", progress))
                    .retrieve()
                    .body(Map.class);
            if (body == null) {
                return new HeartbeatResult(true, false);
            }
            return new HeartbeatResult(
                    Boolean.TRUE.equals(body.get("ok")),
                    Boolean.TRUE.equals(body.get("cancel_requested")));
        } catch (RuntimeException e) {
            // ⚠ 心跳失败**不能**当成"被接管"：网络抖一下就把任务丢掉太草率了。
            //   返回 ok=true 让它继续跑；真正的接管会在交回结果时被 token 挡住。
            //   （只有 scheduling 明确说 ok=false 才是真的失效。）
            log.warn("心跳失败（按仍然有效处理）: job={} {}", jobId, e.getMessage());
            return new HeartbeatResult(true, false);
        }
    }

    /** 交回成功结果。返回 scheduling 的原始响应，由调用方判断 accepted。 */
    public Map<?, ?> submitResult(long jobId, String jobToken, String solverStatus,
                                  String modelFingerprint, Long objectiveValue,
                                  List<TimetableEntry> entries) {
        return http.post()
                .uri("/internal/jobs/{id}/result", jobId)
                .body(Map.of(
                        "job_token", jobToken,
                        "solver_status", solverStatus,
                        "model_fingerprint", modelFingerprint == null ? "" : modelFingerprint,
                        "objective_value", objectiveValue == null ? 0 : objectiveValue,
                        "entries", entries))
                .retrieve()
                .body(Map.class);
    }

    /** 交回失败。{@code retryable} 由 worker 判断（见下）。 */
    public Map<?, ?> submitFailure(long jobId, String jobToken, String error,
                                   String solverStatus, boolean retryable) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("job_token", jobToken);
        body.put("solver_status", solverStatus == null ? "" : solverStatus);
        body.put("error", error);
        body.put("retryable", retryable);
        return http.post()
                .uri("/internal/jobs/{id}/result", jobId)
                .body(body)
                .retrieve()
                .body(Map.class);
    }

    /**
     * 交回"已被取消"。
     *
     * <p>⚠ 它和"失败"是两回事：失败意味着"这次没排出来"（可能需要重试），
     * 取消意味着"用户不想排了"（**绝不该重试**）。混成一个会把用户主动取消的任务
     * 重新拉起来跑。
     */
    public Map<?, ?> submitCancellation(long jobId, String jobToken) {
        return http.post()
                .uri("/internal/jobs/{id}/result", jobId)
                .body(Map.of("job_token", jobToken, "cancelled", true))
                .retrieve()
                .body(Map.class);
    }

    /** 4xx 时不要抛异常堆栈里塞满 HTTP 细节，留给调用方判断。 */
    public String workerId() {
        return properties.getId();
    }
}
