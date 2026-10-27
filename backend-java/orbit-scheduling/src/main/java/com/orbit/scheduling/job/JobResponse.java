package com.orbit.scheduling.job;

import java.time.LocalDateTime;

/**
 * 任务列表 / 详情的一项 —— {@code GET /api/schedule/jobs}。
 *
 * <h2>三个"派生字段"（不落库、每次算）</h2>
 * <ul>
 *   <li>{@link #queuePosition} —— <b>"我前面还有几个"</b>。只对 {@code pending} 有意义，
 *       口径与领单完全一致（都按 id 顺序），否则显示的名次会是错的</li>
 *   <li>{@link #leaseExpired} —— 租约是否已过期。它**每时每刻都在变**，所以不能存成列：
 *       存了就会有一个"库里的值和真实情况不一致"的窗口</li>
 *   <li>{@link #stuck} —— 更直白的结论：`running` 但租约过期 = **持有者可能已经死了**。
 *       界面据此提示"卡住了，等一下会被自动接管"，而不是干等</li>
 * </ul>
 *
 * <p>⚠ 为什么要有 {@code stuck} 而不是让前端自己判断：
 * "running 但 lease_until < now"这个判断需要服务端的时间基准。
 * 客户端的时钟可能差几分钟，用它算会得出相反的结论。
 */
public record JobResponse(
        Long id,
        String status,
        String ruleSetHash,
        Long snapshotId,
        Integer attempt,
        Integer maxAttempts,
        Integer progress,
        Boolean cancelRequested,
        String workerId,
        String lastError,
        String solverStatus,
        Long objectiveValue,
        Long versionId,
        Integer entryCount,
        /** 仅 pending 有意义：同组织里排在它前面的待办数。 */
        Long queuePosition,
        /** running 且租约已过期 —— 持有者大概率已经死了，等待自动接管。 */
        Boolean leaseExpired,
        LocalDateTime leaseUntil,
        LocalDateTime createdAt,
        LocalDateTime startedAt,
        LocalDateTime finishedAt) {

    public static JobResponse of(ScheduleJobEntity job, long queuePosition, boolean leaseExpired) {
        return new JobResponse(
                job.getId(), job.getStatus(), job.getRuleSetHash(), job.getSnapshotId(),
                job.getAttempt(), job.getMaxAttempts(), job.getProgress(),
                job.getCancelRequested(), job.getWorkerId(), job.getLastError(),
                job.getSolverStatus(), job.getObjectiveValue(), job.getVersionId(),
                job.getEntryCount(),
                "pending".equals(job.getStatus()) ? queuePosition : null,
                leaseExpired,
                job.getLeaseUntil(),
                job.getCreatedAt(), job.getStartedAt(), job.getFinishedAt());
    }
}
