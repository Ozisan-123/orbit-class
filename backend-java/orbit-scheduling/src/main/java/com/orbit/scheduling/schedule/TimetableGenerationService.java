package com.orbit.scheduling.schedule;

import com.orbit.solver.core.OrbitSolver;
import com.orbit.solver.core.engine.SolveOutcome;
import com.orbit.solver.core.engine.SolverSettings;
import com.orbit.solver.core.model.ScheduleInput;
import com.orbit.solver.core.rule.ConstraintRule;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 求解封装 —— 把 {@link ScheduleInput} 交给 solver-core 并拿回结果。
 *
 * <h2>规则从哪来：从数据库来（P2 起）</h2>
 * P1 时这里写死的是 {@code RuleSets.legacyEquivalent()} —— 一份**硬编码预设**，
 * 唯一目的是复现 Python 的行为。P2 之后规则改由 {@code constraint_rules} 表提供
 * （{@code rule.RuleSetService#loadForSolve} 载入），于是"加一条约束"
 * 从"改代码重新部署"变成了"在页面上加一行"。
 *
 * <p>⚠ 因此这个类现在**不决定用哪些规则**，只负责编译与求解 —— 规则集是参数，
 * 由 {@link ScheduleRunner} 取。这样"取规则"只有一个地方做，也就只有一个地方会取错。
 *
 * <p>它仍然是方案 §15「边界 ①」的落点，只是内容变了：用**规则系统**编译
 * （{@code compile.RuleCompiler}），而不是 {@code legacy.FaithfulModelBuilder} ——
 * 后者是等价性测试的基线，拿它当生产路径等于把死代码上线。
 */
@Component
public class TimetableGenerationService {

    /**
     * 求解。
     *
     * @param rules            生效的规则集（来自 {@code constraint_rules} 表）
     * @param timeLimitSeconds 对应 Python 的 {@code time_limit=60}
     * @param numSearchWorkers 对应 {@code SCHEDULE_SOLVER_WORKERS}
     */
    public SolveOutcome solve(ScheduleInput input, List<ConstraintRule> rules,
                              int timeLimitSeconds, int numSearchWorkers) {
        SolverSettings settings = new SolverSettings(timeLimitSeconds, numSearchWorkers, false);
        return OrbitSolver.solve(input, rules, settings);
    }
}
