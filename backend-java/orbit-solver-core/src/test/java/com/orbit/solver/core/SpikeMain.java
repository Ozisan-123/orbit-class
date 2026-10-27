package com.orbit.solver.core;

import com.orbit.solver.core.compile.CompiledModel;
import com.orbit.solver.core.engine.SolveOutcome;
import com.orbit.solver.core.engine.SolverSettings;
import com.orbit.solver.core.legacy.FaithfulModelBuilder;
import com.orbit.solver.core.model.ScheduleInput;
import com.orbit.solver.core.rule.ConstraintRule;
import com.orbit.solver.core.rule.RuleHandler;
import com.orbit.solver.core.rule.RuleParamSpec;
import com.orbit.solver.core.rule.RuleParams;
import com.orbit.solver.core.rule.RuleRegistry;
import com.orbit.solver.core.rule.handler.SubjectMaxPerDayHandler;
import com.orbit.solver.core.rule.handler.SubjectNoConsecutiveHandler;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * spike 主程序。
 *
 * <pre>
 *   java ... SpikeMain [fixture 路径] [--solve]
 * </pre>
 *
 * 同时跑两条路并对比：
 * <ul>
 *   <li><b>A 规则系统</b>：最终形态（compile.RuleCompiler + rule/handler）</li>
 *   <li><b>B 忠实移植</b>：测试用基线（legacy.FaithfulModelBuilder），
 *       它的指纹应与 Python 版逐字符相同</li>
 * </ul>
 */
public final class SpikeMain {

    /** Python 侧用同一份 fixture 得到的指纹（见 docs/Java建模等价性验证报告.md）。 */
    private static final String PYTHON_FINGERPRINT = "0x874e810d17f32a8b";

    public static void main(String[] args) throws Exception {
        Path fixture = Path.of(args.length > 0 ? args[0]
                : "orbit-solver-core/src/test/resources/fixtures/school-8.json");
        boolean doSolve = args.length > 1 && "--solve".equals(args[1]);

        ScheduleInput input = FixtureLoader.load(fixture);
        System.out.printf("fixture: %d 班 / %d 教师 / %d 任教关系 / %d 固定课规则%n",
                input.classes().size(), input.teachers().size(),
                input.assignments().size(), input.gradeFixedSlots().size());

        // ---------------------------------------------------------- A. 最终形态
        List<ConstraintRule> rules = legacyEquivalentRules();
        CompiledModel compiled = OrbitSolver.compile(input, rules);
        System.out.println();
        System.out.println("=== A. 规则系统（最终形态：compile + rule/handler）===");
        System.out.println(compiled.modelStats());

        // ---------------------------------------------------------- B. 测试基线
        FaithfulModelBuilder faithful = new FaithfulModelBuilder(input);
        faithful.build();
        // Python 的 minimize() 会写 scaling_factor=1.0，Java 留空；对齐后指纹才可比
        faithful.model().getBuilder().getObjectiveBuilder().setScalingFactor(1.0);
        String legacyStats = faithful.model().modelStats();
        String legacyFingerprint = OrbitSolver.fingerprintOf(faithful.model());
        System.out.println("=== B. 忠实移植（测试基线，应与 Python 一致）===");
        System.out.println(legacyStats);

        // ---------------------------------------------------------- 对比
        System.out.println("=== 对比 ===");
        System.out.printf("  Python 指纹           %s%n", PYTHON_FINGERPRINT);
        System.out.printf("  Java 忠实移植指纹     %s   %s%n", legacyFingerprint,
                PYTHON_FINGERPRINT.equals(legacyFingerprint) ? "一致 ✅" : "不一致 ❌");
        Integer ruleVars = variableCount(compiled.modelStats());
        Integer legacyVars = variableCount(legacyStats);
        System.out.printf("  规则系统变量数        %s（忠实移植 %s，减少 %s，即 %.1f%%）%n",
                ruleVars, legacyVars, legacyVars - ruleVars,
                (legacyVars - ruleVars) * 100.0 / legacyVars);

        // ---------------------------------------------------------- 规则清单
        System.out.println();
        System.out.println("=== 已注册规则（schema 同时驱动 前端表单 / 后端校验 / 助手工具）===");
        RuleRegistry registry = OrbitSolver.builtinRules();
        for (RuleHandler h : registry.all()) {
            StringBuilder sb = new StringBuilder();
            for (RuleParamSpec p : h.params()) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(p.name()).append(p.required() ? "(必填)" : "(可选)");
            }
            System.out.printf("  %-24s %-14s %-9s 参数[%s]%n",
                    h.type(), h.displayName(),
                    h.mandatory() ? "不变量" : "可配置", sb);
        }
        System.out.println("  当前生效规则集:");
        for (ConstraintRule r : rules) {
            System.out.printf("    %s %s weight=%d  %s%n",
                    r.isSoft() ? "软" : "硬", r.type(), r.effectiveWeight(), r.description());
        }

        // ---------------------------------------------------------- 求解
        if (doSolve) {
            System.out.println();
            System.out.println("=== 求解 ===");
            SolveOutcome outcome = OrbitSolver.solve(compiled, new SolverSettings(10, 1, false));
            System.out.printf("status=%s entries=%d objective=%d bound=%d wall=%.2fs%n",
                    outcome.status(), outcome.entries().size(), outcome.objectiveValue(),
                    outcome.bestObjectiveBound(), outcome.wallTimeSeconds());
        }
    }

    /** 与现有 Python 行为等价的一套规则：任何科目一天最多 1 节 + 不连排，权重各为 1。 */
    private static List<ConstraintRule> legacyEquivalentRules() {
        return List.of(
                ConstraintRule.soft(SubjectMaxPerDayHandler.TYPE,
                        RuleParams.of(Map.of("max", 1)), 1, "任何科目一天最多 1 节"),
                ConstraintRule.soft(SubjectNoConsecutiveHandler.TYPE,
                        RuleParams.empty(), 1, "任何科目不连排两节"));
    }

    private static Integer variableCount(String modelStats) {
        int at = modelStats.indexOf("#Variables:");
        if (at < 0) {
            return null;
        }
        String rest = modelStats.substring(at + "#Variables:".length()).trim();
        StringBuilder digits = new StringBuilder();
        for (char c : rest.toCharArray()) {
            if (Character.isDigit(c)) {
                digits.append(c);
            } else if (c == '\'') {
                continue;               // 千分位分隔符，如 9'676
            } else if (digits.length() > 0) {
                break;
            }
        }
        return digits.length() == 0 ? null : Integer.valueOf(digits.toString());
    }

    private SpikeMain() {
    }
}
