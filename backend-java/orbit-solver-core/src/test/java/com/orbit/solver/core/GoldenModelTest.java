package com.orbit.solver.core;

import com.orbit.solver.core.compile.CompiledModel;
import com.orbit.solver.core.legacy.FaithfulModelBuilder;
import com.orbit.solver.core.model.ScheduleInput;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 黄金模型回归：把「模型长什么样」固化成断言。
 *
 * <p>它守的是<b>模型漂移</b>：任何 handler、SlotVariableIndex、RuleCompiler 的改动，
 * 只要改变了模型的变量/约束结构，这里就会红 —— 而这是好事，
 * 因为「模型变了」必须是有意的，并且要重新解释。
 *
 * <p>两个基线：
 * <ul>
 *   <li><b>规则系统</b>（最终形态）：9,676 变量，指纹 {@code 0x3cbfb67312cda176}</li>
 *   <li><b>忠实移植</b>（legacy.FaithfulModelBuilder）：13,452 变量，
 *       指纹 {@code 0x874e810d17f32a8b} —— 与 Python 侧逐字符相同</li>
 * </ul>
 *
 * <p>⚠ 这些数字绑定 <b>ortools-java 9.15.6755</b> 与 <b>fixtures/school-8.json</b>。
 * 升级 OR-Tools 或改夹具都会让它们变化，届时需要重新确认而不是直接改数字。
 */
class GoldenModelTest {

    /** Python 侧用同一份 fixture 得到的指纹。 */
    private static final String PYTHON_FINGERPRINT = "0x874e810d17f32a8b";

    /** 规则系统（compile + rule/handler）的指纹。 */
    private static final String RULESET_FINGERPRINT = "0x3cbfb67312cda176";

    @Test
    @DisplayName("规则系统：模型形状未漂移")
    void 规则系统模型形状未漂移() {
        ScheduleInput input = TestFixtures.school8();
        CompiledModel compiled = OrbitSolver.compile(input, TestFixtures.legacyEquivalentRules());
        String stats = compiled.modelStats();

        assertEquals(9_676, variableCount(stats), "变量总数（§去重后）");
        assertEquals(RULESET_FINGERPRINT, compiled.modelFingerprint(), "模型指纹");
        assertTrue(stats.contains("#bools: 1'572"), "参与目标的布尔数应不变（软约束语义）");
        assertTrue(stats.contains("#ints: 320 in objective"), "参与目标的整数数应不变");
        assertTrue(stats.contains("#kBoolAnd: 5'344"), "kBoolAnd 数");
        assertTrue(stats.contains("#kBoolOr: 5'344"), "kBoolOr 数");
        assertTrue(stats.contains("#kLinear1: 8'201"), "kLinear1 数");
        assertTrue(stats.contains("#kLinear3: 413"), "kLinear3 数");
        assertTrue(stats.contains("#kLinearN: 420"), "kLinearN 数");
    }

    @Test
    @DisplayName("忠实移植：与 Python 逐字符一致")
    void 忠实移植仍与Python一致() {
        ScheduleInput input = TestFixtures.school8();
        FaithfulModelBuilder faithful = new FaithfulModelBuilder(input);
        faithful.build();
        // Python 的 minimize() 会写 objective.scaling_factor=1.0，Java 留空；对齐后指纹才可比
        faithful.model().getBuilder().getObjectiveBuilder().setScalingFactor(1.0);

        String stats = faithful.model().modelStats();
        String fingerprint = OrbitSolver.fingerprintOf(faithful.model());

        assertEquals(13_452, variableCount(stats), "忠实移植的变量总数");
        assertEquals(PYTHON_FINGERPRINT, fingerprint,
                "忠实移植必须与 Python 完全一致；若此处失败，说明基线被破坏了");
    }

    @Test
    @DisplayName("去重效果：正好减少 28.1% 的变量")
    void 去重效果符合预期() {
        ScheduleInput input = TestFixtures.school8();

        FaithfulModelBuilder faithful = new FaithfulModelBuilder(input);
        faithful.build();
        faithful.model().getBuilder().getObjectiveBuilder().setScalingFactor(1.0);
        int legacyVars = variableCount(faithful.model().modelStats());

        int ruleVars = variableCount(
                OrbitSolver.compile(input, TestFixtures.legacyEquivalentRules()).modelStats());

        int removed = legacyVars - ruleVars;
        assertEquals(3_776, removed,
                "消除的重复变量数（教师成员布尔被两条规则共用）");
        assertTrue(Math.abs(removed * 100.0 / legacyVars - 28.1) < 0.1,
                "去重比例应在 28.1% 附近，实际 " + (removed * 100.0 / legacyVars) + "%");
    }

    @Test
    @DisplayName("忠实移植可从文件路径加载（双入口一致）")
    void 夹具双入口一致() throws Exception {
        ScheduleInput fromResource = FixtureLoader.loadResource(FixtureLoader.SCHOOL_8);
        ScheduleInput fromPath = FixtureLoader.load(
                Path.of("src/test/resources/fixtures/school-8.json"));
        assertEquals(fromPath, fromResource, "classpath 与文件系统两个入口应读出同一份数据");
    }

    private static int variableCount(String modelStats) {
        int at = modelStats.indexOf("#Variables:");
        if (at < 0) {
            throw new AssertionError("modelStats 里没有 #Variables: " + modelStats);
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
        return Integer.parseInt(digits.toString());
    }
}
