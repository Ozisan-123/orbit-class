package com.orbit.solver.core;

import com.google.ortools.sat.BoolVar;
import com.google.ortools.sat.CpSolverStatus;
import com.orbit.solver.core.compile.CompiledModel;
import com.orbit.solver.core.engine.SolveOutcome;
import com.orbit.solver.core.engine.SolverSettings;
import com.orbit.solver.core.model.ClassData;
import com.orbit.solver.core.model.ScheduleInput;
import com.orbit.solver.core.rule.ConstraintRule;
import com.orbit.solver.core.rule.RuleContext;
import com.orbit.solver.core.rule.RuleParams;
import com.orbit.solver.core.rule.handler.MainSubjectMorningHandler;
import com.orbit.solver.core.rule.handler.SubjectAvoidLastPeriodHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 两条「科目排在哪一节」的新规则：不排最后一节、主科优先上午。
 *
 * <p>这两条的共同陷阱是「位置要按班按天算」，写成常数会在节数不同的班级/日期上
 * <b>安静地失效</b> —— 约束照加，只是加错了位置，反例测试照样绿。
 * 所以这里专门构造了「周五只有 3 节」的输入来钉住这一点。
 */
class SubjectPlacementRuleTest {

    // ==================================================== 单科不排最后一节

    @Test
    @DisplayName("不排最后一节（硬）：强行排到最后一节 → 不可行")
    void 最后一节的违反解被拒() {
        ScheduleInput input = TestFixtures.school8();
        long subjectId = 2L;
        CompiledModel compiled = OrbitSolver.compile(input, List.of(
                ConstraintRule.hard(SubjectAvoidLastPeriodHandler.TYPE,
                        RuleParams.of(Map.of("subject_id", subjectId)), "不排最后一节")));
        RuleContext ctx = compiled.context();

        long classId = firstClassWithSubject(ctx, subjectId);
        int day = 0;
        int last = ctx.periodsOn(classId, day) - 1;
        BoolVar v = ctx.subjectAt(classId, day, last, subjectId);
        assertNotNull(v, "该班最后一节应不是固定课");
        compiled.model().addEquality(v, 1);

        assertEquals(CpSolverStatus.INFEASIBLE,
                OrbitSolver.solve(compiled, TestFixtures.quickSolve()).status());
    }

    @Test
    @DisplayName("灵敏度反证：没有这条规则时，最后一节可以排")
    void 最后一节_没有规则时被接受() {
        ScheduleInput input = TestFixtures.school8();
        long subjectId = 2L;
        CompiledModel compiled = OrbitSolver.compile(input, List.of());
        RuleContext ctx = compiled.context();

        long classId = firstClassWithSubject(ctx, subjectId);
        int day = 0;
        BoolVar v = ctx.subjectAt(classId, day, ctx.periodsOn(classId, day) - 1, subjectId);
        assertNotNull(v);
        compiled.model().addEquality(v, 1);

        assertNotEquals(CpSolverStatus.INFEASIBLE,
                OrbitSolver.solve(compiled, TestFixtures.quickSolve()).status());
    }

    @Test
    @DisplayName("解出来的课表里，该科目确实不在任何一天的最后一节（独立复核）")
    void 解里最后一节没有该科目() {
        ScheduleInput input = TestFixtures.school8();
        long subjectId = 2L;
        CompiledModel compiled = OrbitSolver.compile(input, List.of(
                ConstraintRule.hard(SubjectAvoidLastPeriodHandler.TYPE,
                        RuleParams.of(Map.of("subject_id", subjectId)), "不排最后一节")));

        SolveOutcome outcome = OrbitSolver.solve(compiled, new SolverSettings(40, 1, false));
        assertTrue(outcome.hasSolution(), "应当可解，实际 " + outcome.status());
        assertEquals(0,
                TestFixtures.countSubjectAtLastPeriod(outcome.entries(), input, subjectId),
                "独立复核：该科目不该出现在任何一天的最后一节");
    }

    @Test
    @DisplayName("「最后一节」按天算：周五只有 3 节时，第 3 节就是最后一节")
    void 最后一节要按天算() {
        ScheduleInput input = withShortFriday(TestFixtures.school8(), 3, 8);
        long subjectId = 2L;
        int friday = 4;

        // 前提：不带规则时，把科目 2 排到周五第 3 节是可满足的。
        // 没有这个前提，下面的 INFEASIBLE 可能来自「容量本来就不够」而不是规则 —— 那是假绿。
        CompiledModel baseline = OrbitSolver.compile(input, List.of());
        long classId = firstClassWithSubject(baseline.context(), subjectId);
        assertEquals(3, baseline.context().periodsOn(classId, friday), "周五应为 3 节");
        BoolVar baselineVar = baseline.context().subjectAt(classId, friday, 2, subjectId);
        assertNotNull(baselineVar, "周五第 3 节应可用");
        baseline.model().addEquality(baselineVar, 1);
        assertNotEquals(CpSolverStatus.INFEASIBLE,
                OrbitSolver.solve(baseline, TestFixtures.quickSolve()).status(),
                "前提：不带规则时这个排法应当可行，否则本测试判不可行的原因不是规则");

        // 加上「不排最后一节」后，同一个排法必须被拦下
        CompiledModel withRule = OrbitSolver.compile(input, List.of(
                ConstraintRule.hard(SubjectAvoidLastPeriodHandler.TYPE,
                        RuleParams.of(Map.of("subject_id", subjectId)), "不排最后一节")));
        withRule.model().addEquality(
                withRule.context().subjectAt(classId, friday, 2, subjectId), 1);

        assertEquals(CpSolverStatus.INFEASIBLE,
                OrbitSolver.solve(withRule, TestFixtures.quickSolve()).status(),
                "周五第 3 节是当天最后一节，硬规则必须拦下 —— "
                        + "若把「最后一节」写成常数（比如永远第 8 节），这里会漏掉");
    }

    @Test
    @DisplayName("不能过度施加：非最后一节强制排该科目，仍然可解")
    void 非最后一节不受影响() {
        ScheduleInput input = withShortFriday(TestFixtures.school8(), 3, 8);
        long subjectId = 2L;

        CompiledModel withoutRule = OrbitSolver.compile(input, List.of());
        long classId = firstClassWithSubject(withoutRule.context(), subjectId);
        // 周一 8 节，第 3 节不是最后一节
        withoutRule.model().addEquality(
                withoutRule.context().subjectAt(classId, 0, 2, subjectId), 1);
        assertNotEquals(CpSolverStatus.INFEASIBLE,
                OrbitSolver.solve(withoutRule, TestFixtures.quickSolve()).status(),
                "前提：不带规则时这个强制是可满足的");

        CompiledModel withRule = OrbitSolver.compile(input, List.of(
                ConstraintRule.hard(SubjectAvoidLastPeriodHandler.TYPE,
                        RuleParams.of(Map.of("subject_id", subjectId)), "不排最后一节")));
        withRule.model().addEquality(
                withRule.context().subjectAt(classId, 0, 2, subjectId), 1);
        assertNotEquals(CpSolverStatus.INFEASIBLE,
                OrbitSolver.solve(withRule, TestFixtures.quickSolve()).status(),
                "周一第 3 节不是最后一节，规则不应禁止");
    }

    // ==================================================== 主科优先上午

    @Test
    @DisplayName("主科优先上午（软）：目标值 == 独立统计的下午课时数")
    void 软上午约束的目标值可被独立复核() {
        ScheduleInput input = TestFixtures.school8();
        int morningPeriods = 4;
        long subjectId = 2L;

        CompiledModel compiled = OrbitSolver.compile(input, List.of(
                ConstraintRule.soft(MainSubjectMorningHandler.TYPE,
                        RuleParams.of(Map.of("subject_id", subjectId,
                                "morning_periods", morningPeriods)), 1, "主科优先上午")));

        SolveOutcome outcome = OrbitSolver.solve(compiled, new SolverSettings(40, 1, false));
        assertTrue(outcome.hasSolution(), "应当可解，实际 " + outcome.status());

        int afternoonLessons = TestFixtures.countSubjectAfterPeriod(
                outcome.entries(), input, subjectId, morningPeriods);
        assertEquals(afternoonLessons, outcome.objectiveValue(),
                "目标值应当等于「该科目排在下午的节数」（独立统计）");
    }

    @Test
    @DisplayName("主科优先上午（硬）：强行排到下午 → 不可行")
    void 硬上午约束的违反解被拒() {
        ScheduleInput input = TestFixtures.school8();
        int morningPeriods = 4;
        long subjectId = 2L;

        CompiledModel compiled = OrbitSolver.compile(input, List.of(
                ConstraintRule.hard(MainSubjectMorningHandler.TYPE,
                        RuleParams.of(Map.of("subject_id", subjectId,
                                "morning_periods", morningPeriods)), "主科不排下午")));
        RuleContext ctx = compiled.context();
        long classId = firstClassWithSubject(ctx, subjectId);

        BoolVar afternoon = findAfternoonVar(ctx, classId, subjectId, morningPeriods);
        assertNotNull(afternoon, "应能找到该班该科的下午时段");
        compiled.model().addEquality(afternoon, 1);

        assertEquals(CpSolverStatus.INFEASIBLE,
                OrbitSolver.solve(compiled, TestFixtures.quickSolve()).status());
    }

    @Test
    @DisplayName("灵敏度反证：没有这条规则时，排到下午会被接受")
    void 硬上午约束_没有规则时被接受() {
        ScheduleInput input = TestFixtures.school8();
        int morningPeriods = 4;
        long subjectId = 2L;

        CompiledModel compiled = OrbitSolver.compile(input, List.of());
        RuleContext ctx = compiled.context();
        long classId = firstClassWithSubject(ctx, subjectId);

        BoolVar afternoon = findAfternoonVar(ctx, classId, subjectId, morningPeriods);
        assertNotNull(afternoon);
        compiled.model().addEquality(afternoon, 1);

        assertNotEquals(CpSolverStatus.INFEASIBLE,
                OrbitSolver.solve(compiled, TestFixtures.quickSolve()).status());
    }

    // ==================================================== 工具

    private static long firstClassWithSubject(RuleContext ctx, long subjectId) {
        for (ClassData cd : ctx.classes()) {
            if (ctx.subjectsOf(cd.classId()).contains(subjectId)) {
                return cd.classId();
            }
        }
        throw new AssertionError("没有班级开设科目 " + subjectId);
    }

    private static BoolVar findAfternoonVar(RuleContext ctx, long classId, long subjectId,
                                            int morningPeriods) {
        for (int day = 0; day < ctx.days(); day++) {
            for (int p = morningPeriods; p < ctx.periodsOn(classId, day); p++) {
                BoolVar b = ctx.subjectAt(classId, day, p, subjectId);
                if (b != null) {
                    return b;
                }
            }
        }
        return null;
    }

    /**
     * 造一个「各天节数不同」的输入。
     *
     * <p>注意 {@code otherDays} 必须给足：原夹具是 6 节 × 5 天 = 30 个座位，
     * 而周课时合计 28 节，只有 2 个富余。若只把周五压短、其他天不动，
     * 总座位会少于 28，模型<b>本来就无解</b> —— 那样测出来的「不可行」
     * 与规则无关，是假绿。
     */
    private static ScheduleInput withShortFriday(ScheduleInput input, int fridayPeriods,
                                                int otherDays) {
        List<ScheduleInput.GradeInfo> grades = input.grades().stream()
                .map(g -> {
                    Map<String, Integer> perDay = new LinkedHashMap<>();
                    perDay.put("Mon", otherDays);
                    perDay.put("Tue", otherDays);
                    perDay.put("Wed", otherDays);
                    perDay.put("Thu", otherDays);
                    perDay.put("Fri", fridayPeriods);
                    return new ScheduleInput.GradeInfo(g.id(), g.name(), perDay);
                })
                .collect(Collectors.toList());
        return new ScheduleInput(input.organizationId(), grades, input.classes(),
                input.subjects(), input.teachers(), input.assignments(),
                input.gradeSubjects(), input.gradeFixedSlots());
    }
}
