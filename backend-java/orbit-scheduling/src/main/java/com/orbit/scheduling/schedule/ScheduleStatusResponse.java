package com.orbit.scheduling.schedule;

import com.orbit.scheduling.query.ScheduleResponse;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 排课状态 —— 对应 Python 的 {@code ScheduleStatus}。
 *
 * <p>实测 Python 的响应（{@code _state_out} 只填 5 个字段）：
 * <pre>{@code
 * {"status":"done","message":"排课完成，已生成草稿 V2，共 92 节课",
 *  "schedules":null,"owner_name":"系统管理员",
 *  "started_at":"2026-09-23T00:58:13","finished_at":"2026-09-23T00:58:13","version_id":null}
 * }</pre>
 * 所以 {@code schedules} 与 {@code version_id} **恒为 null**（Pydantic 的默认值原样输出）。
 * 保留它们是为了逐字对齐契约 —— 前端类型定义里也有这两个字段。
 */
public record ScheduleStatusResponse(String status, String message,
                                     List<ScheduleResponse> schedules,
                                     String ownerName,
                                     LocalDateTime startedAt, LocalDateTime finishedAt,
                                     Long versionId) {

    public static ScheduleStatusResponse from(SchedulingState state) {
        return new ScheduleStatusResponse(state.getStatus(), state.getMessage(),
                null, state.getOwnerName(), state.getStartedAt(), state.getFinishedAt(), null);
    }

    /**
     * 没有状态行时的默认值。
     *
     * <p>⚠ 这是与 Python 的**一处有意偏离**：Python 的 {@code _state_out(None)} 会
     * {@code AttributeError → 500}（它假设行一定存在，因为有"建组织时顺手插一行"的逻辑）。
     * P1 把初始化搬到了属主这边（见方案 §15 的 scheduling_state 决策），
     * 所以这里必须容忍"还没有行"——返回默认的 idle 而不是空指针。
     */
    public static ScheduleStatusResponse idleDefault() {
        return new ScheduleStatusResponse("idle", "尚未开始排课", null, null, null, null, null);
    }
}
