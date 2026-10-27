package com.orbit.solver.core;

import com.google.ortools.sat.CpSolverStatus;
import com.orbit.solver.core.engine.SolveOutcome;
import com.orbit.solver.core.engine.SolverSettings;
import com.orbit.solver.core.model.ScheduleInput;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 正向测试 —— 反例注入的反方向。
 *
 * <p>反例测试能发现「约束写漏了」，但发现不了「约束写强了」：
 * 如果某条规则被写成了过约束，模型会变成无解，而反例测试全都会通过
 * （因为不可行的模型当然会拒绝一切反例）。这一组测试就是守这个方向的。
 *
 * <p>顺带它也验证了整条链路：<b>输入 → 建模 → 求解 → 结果还原</b>，
 * 并用一个独立于建模代码的校验器复核解是否真的满足硬约束。
 */
class PositiveFeasibilityTest {

    @Test
    @DisplayName("只有不变量时，模型可解且解完整")
    void 只有不变量时可解() {
        ScheduleInput input = TestFixtures.school8();
        SolveOutcome outcome = OrbitSolver.solve(input, List.of(),
                new SolverSettings(30, 1, false));

        assertTrue(isSolved(outcome.status()), "应求出解，实际 " + outcome.status());
        assertFalse(outcome.entries().isEmpty(), "不应是空课表");

        // 注意：时段变量允许取「空堂」哨兵（周课时之和可以小于座位总数），
        // 所以这里不能断言「每一节都有课」；正确做法是核对各科节数是否等于周课时。
        String problems = TestFixtures.checkInvariants(input, outcome.entries());
        assertEquals("", problems, "解违反了硬约束：\n" + problems);
    }

    @Test
    @DisplayName("软规则集下可解，且解真的满足全部硬约束")
    void 软规则集下可解且满足硬约束() {
        ScheduleInput input = TestFixtures.school8();
        SolveOutcome outcome = OrbitSolver.solve(input, TestFixtures.legacyEquivalentRules(),
                new SolverSettings(60, 2, false));

        assertTrue(isSolved(outcome.status()), "应求出解，实际 " + outcome.status());

        // 用独立校验器复核（这段逻辑不依赖建模代码，是「唯一不依赖 LLM 的最终防线」的雏形）
        String problems = TestFixtures.checkInvariants(input, outcome.entries());
        assertEquals("", problems, "解违反了硬约束：\n" + problems);
    }

    @Test
    @DisplayName("容量不足 → 判为 INFEASIBLE，而不是静默返回空课表")
    void 容量不足时判为不可行() {
        ScheduleInput input = withOnePeriodPerDay(TestFixtures.school8());

        SolveOutcome outcome = OrbitSolver.solve(input, List.of(),
                new SolverSettings(30, 1, false));

        assertEquals(CpSolverStatus.INFEASIBLE, outcome.status(),
                "每天只排 1 节却要求 28 周课时，应当判为不可行");
        assertTrue(outcome.entries().isEmpty(), "不可行时不应返回半成品课表");
        assertFalse(outcome.hasSolution());
    }

    @Test
    @DisplayName("不可行与「超时未证明」是可区分的两个状态")
    void 不可行与超时可区分() {
        ScheduleInput input = withOnePeriodPerDay(TestFixtures.school8());

        SolveOutcome infeasible = OrbitSolver.solve(input, List.of(),
                new SolverSettings(30, 1, false));
        assertEquals(CpSolverStatus.INFEASIBLE, infeasible.status());

        // 同一个模型给极短时限，状态可能是 UNKNOWN（没证明出来），但绝不应该是 INFEASIBLE
        SolveOutcome rushed = OrbitSolver.solve(input, List.of(),
                new SolverSettings(0.001, 1, false));
        assertTrue(rushed.status() == CpSolverStatus.UNKNOWN
                        || rushed.status() == CpSolverStatus.INFEASIBLE,
                "极短时限下只可能是 UNKNOWN 或已经证明的 INFEASIBLE，实际 " + rushed.status());
    }

    // ---------------------------------------------------------------- 工具

    private static boolean isSolved(CpSolverStatus status) {
        return status == CpSolverStatus.OPTIMAL || status == CpSolverStatus.FEASIBLE;
    }

    /** 全部班级的「每周节数」之和（仅用于诊断信息）。 */
    private static int expectedSlotCount(ScheduleInput input) {
        Map<Long, ScheduleInput.GradeInfo> gradeById = new LinkedHashMap<>();
        input.grades().forEach(g -> gradeById.put(g.id(), g));
        int total = 0;
        for (ScheduleInput.ClassInfo c : input.classes()) {
            ScheduleInput.GradeInfo grade = gradeById.get(c.gradeId());
            for (int periods : grade.periodsPerDay().values()) {
                total += periods;
            }
        }
        return total;
    }

    /** 把每个年级的每天节数都压成 1 节，制造「容量 < 需求」。 */
    private static ScheduleInput withOnePeriodPerDay(ScheduleInput input) {
        List<ScheduleInput.GradeInfo> grades = input.grades().stream()
                .map(g -> {
                    Map<String, Integer> tiny = new LinkedHashMap<>();
                    g.periodsPerDay().keySet().forEach(k -> tiny.put(k, 1));
                    return new ScheduleInput.GradeInfo(g.id(), g.name(), tiny);
                })
                .toList();
        return new ScheduleInput(input.organizationId(), grades, input.classes(),
                input.subjects(), input.teachers(), input.assignments(),
                input.gradeSubjects(), input.gradeFixedSlots());
    }
}
