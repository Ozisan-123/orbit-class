package com.orbit.scheduling.job;

import com.orbit.solver.core.model.ScheduleInput;
import com.orbit.solver.core.rule.ConstraintRule;

import java.util.List;

/**
 * 领单响应 —— scheduling 交给 solver-worker 的**全部输入**（P3）。
 *
 * <p>它一次带齐：任务身份（{@code jobId}/{@code jobToken}）、
 * **冻结的输入快照**、**当时那套规则**、以及租约参数。
 *
 * <p>这样做有两个好处，都是刻意的：
 * <ol>
 *   <li><b>worker 不需要碰数据库</b> —— 与方案 §2.2 服务清单里
 *       solver-worker「拥有的表：无」一致，铁律 2 在 P3 仍然成立。
 *       代价是每次领单多传一份快照，而那是几 KB 的 JSON，可以接受。</li>
 *   <li><b>worker 拿到的是"当时"的输入与规则</b> —— 任务排队期间教务数据或规则被改了
 *       也不影响这次求解，而且事后可复现。</li>
 * </ol>
 *
 * @param takeOver 这一单是不是**接管**来的（原持有者的租约过期了）。
 *                 为 true 时说明上一个 worker 没能收尾 —— 值得在 worker 侧打一条日志。
 */
public record JobClaimResponse(
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
        boolean takeOver) {
}
