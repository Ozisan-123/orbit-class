package com.orbit.scheduling.job;

import com.orbit.common.error.BusinessException;
import com.orbit.scheduling.schedule.ScheduleStateService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 内部接口 —— solver-worker 与 scheduling 之间的私有协议（P3）。
 *
 * <h2>为什么要这三个接口，而不是让 worker 直接读写数据库</h2>
 * 方案 §2.2 的服务清单写明 solver-worker「拥有的表：**无**」。
 * 而 SKIP LOCKED 领单本质上是**写** {@code schedule_jobs}。
 * 让 worker 直接写就违反了铁律 2（禁止跨服务写库）。
 * 所以"领单"变成 scheduling 的内部接口 —— 代价是每次领单多一次 HTTP，
 * 换来的是**表属主始终清晰**，P3 之后拆独立库时不用回头改。
 *
 * <h2>鉴权</h2>
 * 这三个接口**不能要求用户 JWT**（worker 没有用户身份）。
 * 用共享密钥 {@code X-Worker-Secret} —— 内部 API 的常规做法。
 * 生产环境还应该在网络层限制（只有 worker 能访问），P3 的网关会做这件事。
 */
@RestController
@RequestMapping("/internal/jobs")
public class InternalJobController {

    private static final Logger log = LoggerFactory.getLogger(InternalJobController.class);

    public static final String WORKER_SECRET_HEADER = "X-Worker-Secret";

    private final JobQueueService queueService;
    private final JobResultWriter resultWriter;
    private final DiagnosisHook diagnosisHook;
    private final ScheduleStateService stateService;
    private final String expectedSecret;

    public InternalJobController(JobQueueService queueService,
                                 JobResultWriter resultWriter,
                                 DiagnosisHook diagnosisHook,
                                 ScheduleStateService stateService,
                                 @Value("${orbit.worker.secret:orbit-dev-worker}") String expectedSecret) {
        this.queueService = queueService;
        this.resultWriter = resultWriter;
        this.diagnosisHook = diagnosisHook;
        this.stateService = stateService;
        this.expectedSecret = expectedSecret;
    }

    /** 取任务所属组织 —— 只用于更新对外状态。 */
    private long organizationIdOf(long jobId) {
        return queueService.requireJobById(jobId).getOrganizationId();
    }

    /**
     * 领单。没有可领的返回 **204 No Content**（而不是 404）——
     * "暂时没活干"是正常状态，不是错误；worker 会隔几秒再来问。
     */
    @PostMapping("/claim")
    public ResponseEntity<JobClaimResponse> claim(
            @RequestParam(name = "worker_id") String workerId,
            @RequestHeader(name = WORKER_SECRET_HEADER, required = false) String secret) {
        requireWorker(secret);
        JobClaimResponse claim = queueService.claim(workerId);
        return claim == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(claim);
    }

    /**
     * 心跳续租。
     *
     * <p>⚠ 响应里的 {@code ok=false} 表示 **token 已失效**（任务被接管或已终结），
     * worker 应当**立即停止求解** —— 继续算下去的结果也不会被接受。
     */
    @PostMapping("/{jobId}/heartbeat")
    public Map<String, Object> heartbeat(@PathVariable Long jobId,
                                          @RequestBody @Valid HeartbeatRequest request,
                                          @RequestHeader(name = WORKER_SECRET_HEADER, required = false) String secret) {
        requireWorker(secret);
        boolean ok = queueService.heartbeat(jobId, request.jobToken(),
                request.progress() == null ? 0 : request.progress());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", ok);
        // 取消请求从心跳响应回去 —— worker 不碰数据库，这是唯一的通道
        out.put("cancel_requested", ok && queueService.isCancelRequested(jobId, request.jobToken()));
        return out;
    }

    /**
     * 交回结果（成功或失败）。
     *
     * <p>三条返回语义，worker 侧必须区分：
     * <ul>
     *   <li>{@code accepted=true} —— 结果已被采纳（版本已落库）</li>
     *   <li>{@code accepted=false} —— **结果被丢弃**，因为任务已被接管。
     *      这不是错误，worker 不必重试，也**不该报错**</li>
     *   <li>{@code failed=true} —— 失败已记录；{@code requeued=true} 表示已退回队列，
     *       下一次领单（可能是别的 worker）会再做一遍</li>
     * </ul>
     */
    @PostMapping("/{jobId}/result")
    public Map<String, Object> result(@PathVariable Long jobId,
                                       @RequestBody @Valid JobResultRequest request,
                                       @RequestHeader(name = WORKER_SECRET_HEADER, required = false) String secret) {
        requireWorker(secret);
        Map<String, Object> out = new LinkedHashMap<>();

        // ① 取消（优先级最高：它和"失败"是两回事，失败可能被重试、取消绝不该）
        if (Boolean.TRUE.equals(request.cancelled())) {
            boolean recorded = queueService.markCancelled(jobId, request.jobToken());
            out.put("accepted", recorded);
            out.put("cancelled", true);
            if (recorded) {
                stateService.finishByOrganization(organizationIdOf(jobId), "idle", "排课任务已取消");
            }
            return out;
        }

        // ② 失败
        if (request.error() != null && !request.error().isBlank()) {
            boolean retryable = Boolean.TRUE.equals(request.retryable());
            boolean recorded = queueService.fail(jobId, request.jobToken(), request.error(),
                    request.solverStatus(), retryable);
            out.put("accepted", false);
            out.put("failed", recorded);
            out.put("requeued", recorded && retryable);
            if (recorded) {
                // 失败时顺手把诊断算清楚并落库（浅诊断，毫秒级）——
                // 这样用户在任务列表里点开就能看到"为什么失败"
                diagnosisHook.onJobFailed(jobId);
            }
            return out;
        }

        if (request.entries() == null || request.entries().isEmpty()) {
            // 没有明细也没报错 → 这是 worker 的 bug，但别让它污染任务状态
            throw BusinessException.badRequest(
                    "结果里既没有 entries 也没有 error —— worker 应当二选一");
        }

        JobResultWriter.Persisted persisted = resultWriter.persistIfStillOwner(
                jobId, request.jobToken(), request.entries(),
                request.solverStatus(), request.modelFingerprint(), request.objectiveValue());
        if (persisted == null) {
            log.warn("结果被丢弃（任务已被接管）job={}", jobId);
            out.put("accepted", false);
            out.put("reason", "任务已被接管，结果已丢弃");
            return out;
        }
        out.put("accepted", true);
        out.put("version_id", persisted.versionId());
        out.put("version_no", persisted.versionNo());
        out.put("entry_count", persisted.entryCount());
        // 交还槽位 + 更新对外状态（事务之外，见 JobResultWriter.settle 的注释）
        resultWriter.settle(jobId, persisted);
        return out;
    }

    /** 内部接口的鉴权：共享密钥。空密钥配置会在启动日志里被警告（见 {@code scheduling.yml}）。 */
    private void requireWorker(String secret) {
        if (secret == null || !secret.equals(expectedSecret)) {
            throw BusinessException.unauthorized("内部接口需要合法的 worker 密钥");
        }
    }
}
