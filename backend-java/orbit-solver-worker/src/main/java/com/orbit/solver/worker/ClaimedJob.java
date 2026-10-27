package com.orbit.solver.worker;

import com.orbit.solver.core.model.ScheduleInput;
import com.orbit.solver.core.rule.ConstraintRule;

import java.util.List;

/**
 * 领到的任务 —— **worker 侧的镜像**，对应 scheduling 的 {@code JobClaimResponse}。
 *
 * <p>⚠ 这是**刻意重复**的类型，不是疏忽：两个服务之间只有 JSON 契约，
 * 共享 Java 类会让"改一个服务的 DTO"悄悄改变另一个服务的行为。
 * 重复的边界很清楚——只有这层信封；而 {@link ScheduleInput} 与
 * {@link ConstraintRule} 是 solver-core 里的**领域类型**，两边共享是正确的
 * （它们本来就属于求解内核，不属于任何一个服务）。
 */
public record ClaimedJob(
        Long jobId,
        String jobToken,
        Long organizationId,
        Long snapshotId,
        String snapshotFingerprint,
        String ruleSetHash,
        List<ConstraintRule> rules,
        ScheduleInput input,
        int attempt,
        int maxAttempts,
        int leaseSeconds,
        int heartbeatSeconds,
        /** true = 这一单是**接管**来的（原持有者的租约过期了）。 */
        boolean takeOver) {
}
