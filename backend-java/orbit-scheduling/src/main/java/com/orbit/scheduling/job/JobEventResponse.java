package com.orbit.scheduling.job;

import java.time.LocalDateTime;

/**
 * 任务事件 —— {@code GET /api/schedule/jobs/{id}/events}。
 *
 * <p>它不是"日志"（日志已经有了），而是**结构化的状态迁移记录**。
 * 前端把时间线画出来，用户就能回答三个问题：
 * <ul>
 *   <li>「这个任务为什么跑了 3 次」→ 看 {@code takeover} 事件</li>
 *   <li>「求解到底卡在哪」→ 看 {@code heartbeat} 事件的**间隔**（断了就是卡了）</li>
 *   <li>「谁做的」→ 看 {@code worker_id}</li>
 * </ul>
 */
public record JobEventResponse(
        Long id,
        String event,
        String workerId,
        Integer attempt,
        String detail,
        LocalDateTime createdAt) {

    public static JobEventResponse of(ScheduleJobEventEntity entity) {
        return new JobEventResponse(entity.getId(), entity.getEvent(), entity.getWorkerId(),
                entity.getAttempt(), entity.getDetail(), entity.getCreatedAt());
    }
}
