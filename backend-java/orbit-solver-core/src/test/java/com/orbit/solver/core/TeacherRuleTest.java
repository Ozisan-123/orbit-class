package com.orbit.solver.core;

import com.google.ortools.sat.CpSolverStatus;
import com.orbit.solver.core.compile.CompiledModel;
import com.orbit.solver.core.engine.ConstructionInfeasible;
import com.orbit.solver.core.engine.SolveOutcome;
import com.orbit.solver.core.model.ScheduleInput;
import com.orbit.solver.core.rule.ConstraintRule;
import com.orbit.solver.core.rule.RuleContext;
import com.orbit.solver.core.rule.RuleParams;
import com.orbit.solver.core.rule.handler.TeacherMaxDailyPeriodsHandler;
import com.orbit.solver.core.rule.handler.TeacherMinimizeGapsHandler;
import com.orbit.solver.core.rule.handler.TeacherUnavailableHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 三条教师相关的新规则：单日节数上限、不可用时段、不留空档。
 *
 * <p>每条都配「反例注入 + 灵敏度反证」，另外对能求解的规则加了
 * <b>独立校验</b>：从排出来的课表反推指标，再和模型的目标值比对 ——
 * 这才能发现「约束加错了位置」这类安静的错误。
 */
class TeacherRuleTest {

    // ==================================================== 教师单日节数上限

    @Test
    @DisplayName("单日上限：同一天排两节 → 不可行")
    void 单日上限的违反解被拒() {
        ScheduleInput input = TestFixtures.school8();
        CompiledModel probe = OrbitSolver.compile(input, List.of());
        SameDayTwoPeriods spot = findSameDayTwoPeriods(probe.context());
        assertTrue(spot != null, "fixture 里应能找到同一天有两个可用时段的教师");

        CompiledModel compiled = OrbitSolver.compile(input, List.of(
                ConstraintRule.hard(TeacherMaxDailyPeriodsHandler.TYPE,
                        RuleParams.of(Map.of("teacher_id", spot.teacherId(), "max", 1)),
                        "该教师一天最多 1 节")));
        forceTeacherAt(compiled, spot, true);

        assertEquals(CpSolverStatus.INFEASIBLE,
                OrbitSolver.solve(compiled, TestFixtures.quickSolve()).status(),
                "一天上限 1 节却排了两节，应当不可行");
    }

    @Test
    @DisplayName("灵敏度反证：没有这条规则时，同一天两节会被接受")
    void 单日上限_没有规则时被接受() {
        ScheduleInput input = TestFixtures.school8();
        CompiledModel probe = OrbitSolver.compile(input, List.of());
        SameDayTwoPeriods spot = findSameDayTwoPeriods(probe.context());

        CompiledModel compiled = OrbitSolver.compile(input, List.of());
        forceTeacherAt(compiled, spot, true);

        assertNotEquals(CpSolverStatus.INFEASIBLE,
                OrbitSolver.solve(compiled, TestFixtures.quickSolve()).status());
    }

    @Test
    @DisplayName("单日上限：解出来的课表确实不超过上限（独立复核）")
    void 单日上限的解满足上限() {
        ScheduleInput input = TestFixtures.school8();
        long teacherId = input.teachers().get(0).id();
        CompiledModel compiled = OrbitSolver.compile(input, List.of(
                ConstraintRule.hard(TeacherMaxDailyPeriodsHandler.TYPE,
                        RuleParams.of(Map.of("teacher_id", teacherId, "max", 2)),
                        "该教师一天最多 2 节")));

        SolveOutcome outcome = OrbitSolver.solve(compiled, new com.orbit.solver.core.engine
                .SolverSettings(30, 1, false));
        assertTrue(outcome.hasSolution(), "应当可解，实际 " + outcome.status());
        for (int day = 0; day < 5; day++) {
            int lessons = TestFixtures.countTeacherLessonsOnDay(outcome.entries(), teacherId, day);
            assertTrue(lessons <= 2,
                    "第 " + (day + 1) + " 天排了 " + lessons + " 节，超过上限 2");
        }
    }

    // ==================================================== 教师不可用时段

    @Test
    @DisplayName("不可用时段：解出来的课表里该时段确实没有这位教师（独立复核）")
    void 不可用时段生效() {
        ScheduleInput input = TestFixtures.school8();
        CompiledModel probe = OrbitSolver.compile(input, List.of());
        long teacherId = input.teachers().get(0).id();
        int[] slot = findFreeTeacherSlot(probe.context(), teacherId);

        CompiledModel compiled = OrbitSolver.compile(input, List.of(
                ConstraintRule.hard(TeacherUnavailableHandler.TYPE,
                        RuleParams.of(Map.of("teacher_id", teacherId,
                                "day", slot[0] + 1, "period", slot[1] + 1)),
                        "该教师此节不可用")));

        SolveOutcome outcome = OrbitSolver.solve(compiled, new com.orbit.solver.core.engine
                .SolverSettings(30, 1, false));
        assertTrue(outcome.hasSolution(), "应当可解，实际 " + outcome.status());
        assertEquals(0, TestFixtures.countTeacherScheduledAt(
                        outcome.entries(), teacherId, slot[0], slot[1]),
                "被设为不可用的时段不应出现这位教师");
    }

    @Test
    @DisplayName("不可用时段：强行把该时段排给这位教师 → 不可行")
    void 不可用时段的违反解被拒() {
        ScheduleInput input = TestFixtures.school8();
        CompiledModel probe = OrbitSolver.compile(input, List.of());
        long teacherId = input.teachers().get(0).id();
        int[] slot = findFreeTeacherSlot(probe.context(), teacherId);

        CompiledModel compiled = OrbitSolver.compile(input, List.of(
                ConstraintRule.hard(TeacherUnavailableHandler.TYPE,
                        RuleParams.of(Map.of("teacher_id", teacherId,
                                "day", slot[0] + 1, "period", slot[1] + 1)),
                        "该教师此节不可用")));
        compiled.model().addEquality(
                compiled.context().freeTeacherSlots(teacherId, slot[0], slot[1]).get(0), 1);

        assertEquals(CpSolverStatus.INFEASIBLE,
                OrbitSolver.solve(compiled, TestFixtures.quickSolve()).status());
    }

    @Test
    @DisplayName("灵敏度反证：没有这条规则时，该时段可以排给这位教师")
    void 不可用时段_没有规则时被接受() {
        ScheduleInput input = TestFixtures.school8();
        CompiledModel probe = OrbitSolver.compile(input, List.of());
        long teacherId = input.teachers().get(0).id();
        int[] slot = findFreeTeacherSlot(probe.context(), teacherId);

        CompiledModel compiled = OrbitSolver.compile(input, List.of());
        compiled.model().addEquality(
                compiled.context().freeTeacherSlots(teacherId, slot[0], slot[1]).get(0), 1);

        assertNotEquals(CpSolverStatus.INFEASIBLE,
                OrbitSolver.solve(compiled, TestFixtures.quickSolve()).status());
    }

    @Test
    @DisplayName("不可用时段与固定课冲突时 → 抛配置错误，而不是求解器判无解")
    void 不可用时段与固定课冲突() {
        ScheduleInput input = TestFixtures.school8();
        CompiledModel probe = OrbitSolver.compile(input, List.of());
        RuleContext ctx = probe.context();

        long fixedTeacher = -1;
        int fixedDay = -1;
        int fixedPeriod = -1;
        for (ScheduleInput.ClassInfo c : input.classes()) {
            for (int day = 0; day < ctx.days() && fixedTeacher < 0; day++) {
                for (int period = 0; period < ctx.periodsOn(c.id(), day); period++) {
                    if (ctx.isFixed(c.id(), day, period)) {
                        fixedTeacher = ctx.fixedTeacherAt(c.id(), day, period);
                        fixedDay = day;
                        fixedPeriod = period;
                        break;
                    }
                }
            }
            if (fixedTeacher >= 0) {
                break;
            }
        }
        assertTrue(fixedTeacher >= 0, "fixture 里应有固定课");

        final long tid = fixedTeacher;
        final int day = fixedDay;
        final int period = fixedPeriod;
        ConstructionInfeasible e = assertThrows(ConstructionInfeasible.class,
                () -> OrbitSolver.compile(input, List.of(
                        ConstraintRule.hard(TeacherUnavailableHandler.TYPE,
                                RuleParams.of(Map.of("teacher_id", tid,
                                        "day", day + 1, "period", period + 1)),
                                "与固定课冲突"))));
        assertTrue(e.getMessage().contains("自相矛盾"), e.getMessage());
    }

    @Test
    @DisplayName("不可用时段：引用不存在的教师 → 配置错误")
    void 不可用时段引用不存在的教师() {
        ScheduleInput input = TestFixtures.school8();
        assertThrows(ConstructionInfeasible.class,
                () -> OrbitSolver.compile(input, List.of(
                        ConstraintRule.hard(TeacherUnavailableHandler.TYPE,
                                RuleParams.of(Map.of("teacher_id", 999_999L,
                                        "day", 1, "period", 1)),
                                "不存在的教师"))));
    }

    // ==================================================== 教师不留空档

    @Test
    @DisplayName("不留空档（硬）：中间节被占死、必须留出空档 → 不可行")
    void 硬空档约束拒绝无法避免的空档() {
        ScheduleInput input = TestFixtures.school8();
        CompiledModel probe = OrbitSolver.compile(input, List.of());
        GapSpot spot = findGapSpot(probe.context());
        assertTrue(spot != null, "fixture 里应能找到可构造空档的位置");

        // 把中间那节封死（模拟教师确实没法上），再强制前后两节都有课
        CompiledModel compiled = OrbitSolver.compile(input, List.of(
                ConstraintRule.hard(TeacherMinimizeGapsHandler.TYPE,
                        RuleParams.empty(), "不留空档")));
        forceGap(compiled, spot);

        assertEquals(CpSolverStatus.INFEASIBLE,
                OrbitSolver.solve(compiled, TestFixtures.quickSolve()).status(),
                "空档无法避免时，硬约束应当判不可行");
    }

    @Test
    @DisplayName("灵敏度反证：没有这条规则时，同一个空档会被接受")
    void 硬空档约束_没有规则时被接受() {
        ScheduleInput input = TestFixtures.school8();
        CompiledModel probe = OrbitSolver.compile(input, List.of());
        GapSpot spot = findGapSpot(probe.context());

        CompiledModel compiled = OrbitSolver.compile(input, List.of());
        forceGap(compiled, spot);

        assertNotEquals(CpSolverStatus.INFEASIBLE,
                OrbitSolver.solve(compiled, TestFixtures.quickSolve()).status());
    }

    @Test
    @DisplayName("不留空档（软）：目标值 == 独立统计出来的空档数")
    void 软空档约束的目标值可被独立复核() {
        ScheduleInput input = TestFixtures.school8();
        CompiledModel probe = OrbitSolver.compile(input, List.of());
        GapSpot spot = findGapSpot(probe.context());

        CompiledModel compiled = OrbitSolver.compile(input, List.of(
                ConstraintRule.soft(TeacherMinimizeGapsHandler.TYPE,
                        RuleParams.empty(), 1, "尽量不留空档")));
        forceGap(compiled, spot);

        SolveOutcome outcome = OrbitSolver.solve(compiled,
                new com.orbit.solver.core.engine.SolverSettings(40, 1, false));
        assertTrue(outcome.hasSolution(), "软约束不应导致无解，实际 " + outcome.status());

        // 独立复核：从课表反推空档数，必须等于模型自己报告的目标值。
        // 这一步能抓住「空洞编码写错」的所有形式：把末节误算成空档、
        // 当天没课算出负数、漏掉某天……
        int actualGaps = TestFixtures.countTeacherGaps(outcome.entries());
        assertTrue(actualGaps >= 1,
                "构造出来的空档应当真的出现在课表里，实际空档数 " + actualGaps);
        assertEquals(actualGaps, outcome.objectiveValue(),
                "模型目标值应当等于独立统计的空档数");
    }

    // ==================================================== 工具

    private record SameDayTwoPeriods(long teacherId, int day, int period1, int period2) {
    }

    private record GapSpot(long teacherId, int day, int before, int hole, int after) {
    }

    private static SameDayTwoPeriods findSameDayTwoPeriods(RuleContext ctx) {
        for (ScheduleInput.TeacherInfo t : ctx.teachers()) {
            for (int day = 0; day < ctx.days(); day++) {
                List<Integer> available = new ArrayList<>();
                for (int p = 0; p < ctx.maxPeriodsOn(day); p++) {
                    if (!ctx.freeTeacherSlots(t.id(), day, p).isEmpty()) {
                        available.add(p);
                    }
                }
                if (available.size() >= 2) {
                    return new SameDayTwoPeriods(t.id(), day, available.get(0), available.get(1));
                }
            }
        }
        return null;
    }

    private static int[] findFreeTeacherSlot(RuleContext ctx, long teacherId) {
        for (int day = 0; day < ctx.days(); day++) {
            for (int p = 0; p < ctx.maxPeriodsOn(day); p++) {
                if (!ctx.freeTeacherSlots(teacherId, day, p).isEmpty()) {
                    return new int[] {day, p};
                }
            }
        }
        throw new AssertionError("该教师没有任何可用时段");
    }

    /** 找一个「前后都能排、中间那节也可以封死」的三连节位置。 */
    private static GapSpot findGapSpot(RuleContext ctx) {
        for (ScheduleInput.TeacherInfo t : ctx.teachers()) {
            for (int day = 0; day < ctx.days(); day++) {
                int max = ctx.maxPeriodsOn(day);
                for (int p = 0; p + 2 < max; p++) {
                    if (!ctx.freeTeacherSlots(t.id(), day, p).isEmpty()
                            && !ctx.freeTeacherSlots(t.id(), day, p + 1).isEmpty()
                            && !ctx.freeTeacherSlots(t.id(), day, p + 2).isEmpty()) {
                        return new GapSpot(t.id(), day, p, p + 1, p + 2);
                    }
                }
            }
        }
        return null;
    }

    private static void forceTeacherAt(CompiledModel compiled, SameDayTwoPeriods spot,
                                       boolean on) {
        RuleContext ctx = compiled.context();
        long v = on ? 1 : 0;
        compiled.model().addEquality(ctx.freeTeacherSlots(spot.teacherId(), spot.day(),
                spot.period1()).get(0), v);
        compiled.model().addEquality(ctx.freeTeacherSlots(spot.teacherId(), spot.day(),
                spot.period2()).get(0), v);
    }

    /** 强制：前后两节有课，中间那节封死 → 必然产生一个空档。 */
    private static void forceGap(CompiledModel compiled, GapSpot spot) {
        RuleContext ctx = compiled.context();
        for (var v : ctx.freeTeacherSlots(spot.teacherId(), spot.day(), spot.hole())) {
            compiled.model().addEquality(v, 0);
        }
        compiled.model().addEquality(
                ctx.freeTeacherSlots(spot.teacherId(), spot.day(), spot.before()).get(0), 1);
        compiled.model().addEquality(
                ctx.freeTeacherSlots(spot.teacherId(), spot.day(), spot.after()).get(0), 1);
    }
}
