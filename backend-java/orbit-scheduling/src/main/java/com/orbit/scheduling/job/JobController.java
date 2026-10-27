package com.orbit.scheduling.job;

import com.orbit.common.dto.PageQuery;
import com.orbit.common.dto.PageResponse;
import com.orbit.common.error.BusinessException;
import com.orbit.common.util.UtcTimes;
import com.orbit.scheduling.security.SchedulePrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 任务接口 —— {@code /api/schedule/jobs/*}（P3 新增，Python 侧没有对应物）。
 *
 * <h2>为什么需要它</h2>
 * P1/P2 的"排课状态"只有一行组织级状态（`scheduling_state`），它能回答
 * "现在在跑吗 / 完成了吗"，但**回答不了**：
 * <ul>
 *   <li>「我排第几」→ 队列里有几个待办</li>
 *   <li>「上次为什么失败」→ 错误信息、重试了几次、被接管过吗</li>
 *   <li>「能重试吗 / 能取消吗」</li>
 * </ul>
 * 有了任务队列，这些都有据可查。
 *
 * <h2>关于 SSE</h2>
 * 方案 §13.2 给 {@code /events} 标注的是「（SSE）实时进度，替代现在的 1 秒轮询」，
 * 并注明**可选**。本期先返回**普通 JSON 事件列表**（前端按需拉取）——
 * SSE 的价值在于"长时间盯着看"，而排课任务通常几十秒就结束，
 * 为它引入一条长连接 + 断线重连 + 代理超时的复杂度不划算。
 * 真要做，等"任务动辄几分钟"的场景出现（那是 P5 压测阶段的事）。
 */
@RestController
@RequestMapping("/api/schedule/jobs")
public class JobController {

    private final JobQueueService queueService;

    public JobController(JobQueueService queueService) {
        this.queueService = queueService;
    }

    /**
     * 任务列表（可按状态筛选 + 分页）。
     *
     * <p>{@code status} 取值：{@code pending} / {@code running} / {@code done}
     * / {@code failed} / {@code cancelled}；不传即全部。
     */
    @GetMapping
    public PageResponse<JobResponse> list(
            @RequestParam(name = "status", required = false) String status,
            PageQuery query,
            @AuthenticationPrincipal SchedulePrincipal principal) {
        long organizationId = requirePrincipal(principal).organizationId();
        PageResponse<ScheduleJobEntity> page = queueService.listByPage(organizationId, status, query);
        List<JobResponse> items = page.items().stream().map(job -> toResponse(organizationId, job)).toList();
        return PageResponse.of(items, page.total());
    }

    @GetMapping("/{jobId}")
    public JobResponse detail(@PathVariable Long jobId,
                              @AuthenticationPrincipal SchedulePrincipal principal) {
        long organizationId = requirePrincipal(principal).organizationId();
        return toResponse(organizationId, queueService.requireJob(organizationId, jobId));
    }

    /**
     * 重试 —— 只对**失败**或**已取消**的任务有意义。
     *
     * <p>⚠ 成功完成的任务不能重试（那会凭空多出一版课表）；正在跑/排队的也不能
     * （它本来就会跑）。所以这里会 400 拒绝，而不是"顺手重置一下"。
     */
    @PostMapping("/{jobId}/retry")
    public JobResponse retry(@PathVariable Long jobId,
                             @AuthenticationPrincipal SchedulePrincipal principal) {
        long organizationId = requirePrincipal(principal).organizationId();
        return toResponse(organizationId, queueService.retry(organizationId, jobId));
    }

    /**
     * 取消。
     *
     * <p>两种路径：
     * <ul>
     *   <li>还没被领走 → **立刻终结**（worker 根本不会看到它）</li>
     *   <li>正在跑 → 只打标记，worker 在下次心跳时看到并收手。
     *       ⚠ 求解调用本身无法中断，所以最坏要等这次求解跑完
     *       （见 {@code JobRunner} 的类注释）</li>
     * </ul>
     */
    @PostMapping("/{jobId}/cancel")
    public JobResponse cancel(@PathVariable Long jobId,
                              @AuthenticationPrincipal SchedulePrincipal principal) {
        long organizationId = requirePrincipal(principal).organizationId();
        return toResponse(organizationId, queueService.cancel(organizationId, jobId));
    }

    /** 事件流（时间线）。 */
    @GetMapping("/{jobId}/events")
    public List<JobEventResponse> events(@PathVariable Long jobId,
                                         @AuthenticationPrincipal SchedulePrincipal principal) {
        long organizationId = requirePrincipal(principal).organizationId();
        // 先确认这个任务属于本组织（否则任何人都能看别人的事件流）
        queueService.requireJob(organizationId, jobId);
        return queueService.events(jobId).stream().map(JobEventResponse::of).toList();
    }

    private JobResponse toResponse(long organizationId, ScheduleJobEntity job) {
        long position = "pending".equals(job.getStatus())
                ? queueService.queuePosition(organizationId, job.getId()) : 0;
        return JobResponse.of(job, position, queueService.isLeaseExpired(job, UtcTimes.now()));
    }

    private static SchedulePrincipal requirePrincipal(SchedulePrincipal principal) {
        if (principal == null) {
            throw BusinessException.unauthorized("请先登录");
        }
        return principal;
    }
}
