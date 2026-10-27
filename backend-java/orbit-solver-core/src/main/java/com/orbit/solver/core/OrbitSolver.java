package com.orbit.solver.core;

import com.google.ortools.sat.CpModel;
import com.google.ortools.sat.CpSolver;
import com.google.ortools.sat.CpSolverStatus;
import com.orbit.solver.core.compile.BuiltinRules;
import com.orbit.solver.core.compile.CompiledModel;
import com.orbit.solver.core.compile.RuleCompiler;
import com.orbit.solver.core.engine.ModelFingerprints;
import com.orbit.solver.core.engine.SolveOutcome;
import com.orbit.solver.core.engine.SolverNatives;
import com.orbit.solver.core.engine.SolverSettings;
import com.orbit.solver.core.engine.TimetableExtractor;
import com.orbit.solver.core.model.ScheduleInput;
import com.orbit.solver.core.model.TimetableEntry;
import com.orbit.solver.core.rule.ConstraintRule;
import com.orbit.solver.core.rule.RuleRegistry;

import java.util.List;

/**
 * 求解内核对外的<b>唯一门面</b>。
 *
 * <p>它位于根包（依赖图的最上层），因此可以同时依赖 {@code compile} 与 {@code engine}
 * 而不产生包环；反过来没有任何包依赖它。
 *
 * <p>典型用法：
 * <pre>{@code
 * List<ConstraintRule> rules = ...;                       // 来自 constraint_rules 表
 * CompiledModel compiled = OrbitSolver.compile(input, rules);
 * SolveOutcome outcome = OrbitSolver.solve(compiled, SolverSettings.production());
 * if (outcome.hasSolution()) { ... outcome.entries() ... }
 * }</pre>
 *
 * <p>{@code solve} 与 {@code compile} 刻意分开：指比对、规则「试算影响」、
 * 不可行性诊断都只需要编译结果，不需要真的求解。
 */
public final class OrbitSolver {

    /** 系统内置的规则注册表（3 个不变量/数据驱动 + 2 个可配置）。 */
    public static RuleRegistry builtinRules() {
        return BuiltinRules.registry();
    }

    /** 编译：把「输入 + 规则集」变成 CP-SAT 模型（不求解）。 */
    public static CompiledModel compile(ScheduleInput input, List<ConstraintRule> rules) {
        return compile(input, rules, BuiltinRules.registry());
    }

    public static CompiledModel compile(ScheduleInput input, List<ConstraintRule> rules,
                                        RuleRegistry registry) {
        SolverNatives.load();
        return new RuleCompiler(registry).compile(input, rules);
    }

    /** 求解一个已编译的模型。 */
    public static SolveOutcome solve(CompiledModel compiled, SolverSettings settings) {
        SolverNatives.load();
        CpSolver solver = new CpSolver();
        solver.getParameters().setMaxTimeInSeconds(settings.maxTimeInSeconds());
        solver.getParameters().setNumSearchWorkers(settings.numSearchWorkers());
        solver.getParameters().setLogSearchProgress(settings.logSearchProgress());

        CpSolverStatus status = solver.solve(compiled.model());
        List<TimetableEntry> entries =
                (status == CpSolverStatus.OPTIMAL || status == CpSolverStatus.FEASIBLE)
                        ? TimetableExtractor.extract(solver, compiled.index())
                        : List.of();

        return new SolveOutcome(status, entries, compiled.modelStats(),
                compiled.modelFingerprint(), solver.wallTime(),
                (long) solver.objectiveValue(), (long) solver.bestObjectiveBound());
    }

    /** 一步到位：编译并求解。 */
    public static SolveOutcome solve(ScheduleInput input, List<ConstraintRule> rules,
                                     SolverSettings settings) {
        return solve(compile(input, rules), settings);
    }

    public static String fingerprintOf(CpModel model) {
        return ModelFingerprints.of(model);
    }

    private OrbitSolver() {
    }
}
