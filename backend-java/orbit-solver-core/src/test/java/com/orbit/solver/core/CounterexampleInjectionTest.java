package com.orbit.solver.core;

import com.google.ortools.sat.BoolVar;
import com.google.ortools.sat.CpSolverStatus;
import com.orbit.solver.core.compile.CompiledModel;
import com.orbit.solver.core.engine.SolveOutcome;
import com.orbit.solver.core.model.ScheduleInput;
import com.orbit.solver.core.rule.ConstraintRule;
import com.orbit.solver.core.rule.RuleParams;
import com.orbit.solver.core.rule.RuleRegistry;
import com.orbit.solver.core.rule.handler.SubjectMaxPerDayHandler;
import com.orbit.solver.core.rule.handler.SubjectWeeklyHoursHandler;
import com.orbit.solver.core.rule.handler.TeacherNoConflictHandler;
import com.orbit.solver.core.rule.handler.TeacherWeeklyLimitHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 反例注入测试 —— <b>规则系统可验证性的地基</b>。
 *
 * <p>做法：对每条规则构造一个<b>恰好违反它</b>的解（把相关变量强制成违规取值），
 * 然后断言模型判为不可行。
 * <ul>
 *   <li>规则<b>漏加约束</b> → 模型仍可解 → 测试红</li>
 *   <li>规则<b>写错方向</b>（比如写成 ≤ 却漏了固定课）→ 同样红</li>
 * </ul>
 *
 * <p>更重要的是每条规则都配一个<b>灵敏度反证</b>：把该规则从注册表里拿掉后，
 * 同一个违反解必须被接受。没有这一步，反例测试可能"因为别的原因恰好不可行"而永远绿 ——
 * 那是假绿。这一条正是「故意注释掉一行约束，对应测试必须变红」的自动化版本。
 */
class CounterexampleInjectionTest {

    // ============================================================ 不变量：教师时段不冲突

    @Test
    @DisplayName("教师时段不冲突：同一教师同一时段的两个班同时上课 → 不可行")
    void 教师冲突的违反解被拒() {
        ScheduleInput input = TestFixtures.school8();
        CompiledModel compiled = OrbitSolver.compile(input, List.of());
        var ctx = compiled.context();

        boolean forced = false;
        outer:
        for (ScheduleInput.TeacherInfo teacher : input.teachers()) {
            for (ScheduleInput.ClassInfo a : input.classes()) {
                for (ScheduleInput.ClassInfo b : input.classes()) {
                    if (a.id() >= b.id() || !ctx.teacherTeachesClass(teacher.id(), a.id())
                            || !ctx.teacherTeachesClass(teacher.id(), b.id())) {
                        continue;
                    }
                    for (int day = 0; day < ctx.days() && !forced; day++) {
                        for (int period = 0; period < ctx.periodsOn(a.id(), day); period++) {
                            BoolVar va = ctx.teacherAt(teacher.id(), a.id(), day, period);
                            BoolVar vb = ctx.teacherAt(teacher.id(), b.id(), day, period);
                            if (va != null && vb != null) {
                                compiled.model().addEquality(va, 1);
                                compiled.model().addEquality(vb, 1);
                                forced = true;
                                break outer;
                            }
                        }
                    }
                }
            }
        }
        assertTrue(forced, "fixture 里应能找到「一个教师同时在两个班上课」的构造");

        assertEquals(CpSolverStatus.INFEASIBLE,
                OrbitSolver.solve(compiled, TestFixtures.quickSolve()).status(),
                "教师冲突是不变量，模型必须拒绝这个解");
    }

    @Test
    @DisplayName("灵敏度反证：注册表里拿掉「教师不冲突」后，同一个违反解会被接受")
    void 教师冲突_去掉规则后违反解被接受() {
        ScheduleInput input = TestFixtures.school8();
        RuleRegistry withoutConflict =
                new RuleRegistry(List.of(new SubjectWeeklyHoursHandler()));
        CompiledModel compiled = OrbitSolver.compile(input, List.of(), withoutConflict);
        var ctx = compiled.context();

        boolean forced = false;
        outer:
        for (ScheduleInput.TeacherInfo teacher : input.teachers()) {
            for (ScheduleInput.ClassInfo a : input.classes()) {
                for (ScheduleInput.ClassInfo b : input.classes()) {
                    if (a.id() >= b.id() || !ctx.teacherTeachesClass(teacher.id(), a.id())
                            || !ctx.teacherTeachesClass(teacher.id(), b.id())) {
                        continue;
                    }
                    for (int day = 0; day < ctx.days(); day++) {
                        for (int period = 0; period < ctx.periodsOn(a.id(), day); period++) {
                            BoolVar va = ctx.teacherAt(teacher.id(), a.id(), day, period);
                            BoolVar vb = ctx.teacherAt(teacher.id(), b.id(), day, period);
                            if (va != null && vb != null) {
                                compiled.model().addEquality(va, 1);
                                compiled.model().addEquality(vb, 1);
                                forced = true;
                                break outer;
                            }
                        }
                    }
                }
            }
        }
        assertTrue(forced);

        assertNotEquals(CpSolverStatus.INFEASIBLE,
                OrbitSolver.solve(compiled, TestFixtures.quickSolve()).status(),
                "拿掉约束后同一个解应被接受 —— 否则上一个测试可能在测别的东西");
    }

    // ============================================================ 数据驱动：周课时数

    @Test
    @DisplayName("周课时数：某科被排得比规定多 → 不可行")
    void 周课时超额的违反解被拒() {
        ScheduleInput input = TestFixtures.school8();
        CompiledModel compiled = OrbitSolver.compile(input, List.of());
        var ctx = compiled.context();

        long classId = input.classes().get(0).id();
        Map<Long, Integer> hours = ctx.weeklyHoursOf(classId);
        long subjectId = hours.keySet().iterator().next();

        int allowed = hours.get(subjectId) - ctx.fixedCountForSubject(classId, subjectId);
        List<BoolVar> slots = ctx.freeSubjectSlots(classId, subjectId);
        assertTrue(slots.size() > allowed,
                "需要该科有富余时段才能构造超额反例");

        for (int i = 0; i <= allowed; i++) {          // 故意多排 1 节
            compiled.model().addEquality(slots.get(i), 1);
        }

        assertEquals(CpSolverStatus.INFEASIBLE,
                OrbitSolver.solve(compiled, TestFixtures.quickSolve()).status(),
                "超过周课时应当不可行");
    }

    @Test
    @DisplayName("灵敏度反证：拿掉「周课时数」后，超额解会被接受")
    void 周课时_去掉规则后超额解被接受() {
        ScheduleInput input = TestFixtures.school8();
        RuleRegistry withoutHours =
                new RuleRegistry(List.of(new TeacherNoConflictHandler()));
        CompiledModel compiled = OrbitSolver.compile(input, List.of(), withoutHours);
        var ctx = compiled.context();

        long classId = input.classes().get(0).id();
        Map<Long, Integer> hours = ctx.weeklyHoursOf(classId);
        long subjectId = hours.keySet().iterator().next();
        List<BoolVar> slots = ctx.freeSubjectSlots(classId, subjectId);

        for (int i = 0; i <= Math.min(hours.get(subjectId), slots.size() - 1); i++) {
            compiled.model().addEquality(slots.get(i), 1);
        }

        assertNotEquals(CpSolverStatus.INFEASIBLE,
                OrbitSolver.solve(compiled, TestFixtures.quickSolve()).status());
    }

    // ============================================================ 数据驱动：教师周上限

    @Test
    @DisplayName("教师周上限：把上限压到 1 后连排两节 → 不可行")
    void 教师周上限的违反解被拒() {
        ScheduleInput input = TestFixtures.school8();
        long teacherId = input.teachers().get(0).id();
        ScheduleInput tight = withTeacherLimit(input, teacherId, 1);

        CompiledModel compiled = OrbitSolver.compile(tight, List.of());
        var ctx = compiled.context();

        List<BoolVar> slots = ctx.freeTeacherSlots(teacherId);
        assertTrue(slots.size() >= 2, "该教师应有至少两个可用时段");
        compiled.model().addEquality(slots.get(0), 1);
        compiled.model().addEquality(slots.get(1), 1);

        assertEquals(CpSolverStatus.INFEASIBLE,
                OrbitSolver.solve(compiled, TestFixtures.quickSolve()).status(),
                "教师课时超过 max_weekly_hours 应当不可行");
    }

    @Test
    @DisplayName("灵敏度反证：拿掉「教师周上限」后，同一个解会被接受")
    void 教师周上限_去掉规则后违反解被接受() {
        ScheduleInput input = TestFixtures.school8();
        long teacherId = input.teachers().get(0).id();
        ScheduleInput tight = withTeacherLimit(input, teacherId, 1);

        RuleRegistry withoutTeacherLimit =
                new RuleRegistry(List.of(new SubjectWeeklyHoursHandler()));
        CompiledModel compiled = OrbitSolver.compile(tight, List.of(), withoutTeacherLimit);
        var ctx = compiled.context();

        // 选两个不同 (day, period) 的时段，避免与教师不冲突纠缠
        List<BoolVar> slots = ctx.freeTeacherSlots(teacherId);
        assertTrue(slots.size() >= 2);
        compiled.model().addEquality(slots.get(0), 1);
        compiled.model().addEquality(slots.get(1), 1);

        assertNotEquals(CpSolverStatus.INFEASIBLE,
                OrbitSolver.solve(compiled, TestFixtures.quickSolve()).status());
    }

    // ============================================================ 可配置硬规则

    @Test
    @DisplayName("单日单科上限（硬）：同一天排两节 → 不可行")
    void 单日单科上限的违反解被拒() {
        ScheduleInput input = TestFixtures.school8();
        long subjectId = 1L;
        CompiledModel compiled = OrbitSolver.compile(input, List.of(
                ConstraintRule.hard(SubjectMaxPerDayHandler.TYPE,
                        RuleParams.of(Map.of("subject_id", subjectId, "max", 1)), "硬上限")));
        var ctx = compiled.context();

        long classId = input.classes().get(0).id();
        int day = pickDayWithTwoFreeSlots(compiled, classId, subjectId);
        forceTwoOnSameDay(compiled, classId, subjectId, day);

        assertEquals(CpSolverStatus.INFEASIBLE,
                OrbitSolver.solve(compiled, TestFixtures.quickSolve()).status(),
                "硬上限为 1 时，同一天两节应当不可行");
    }

    @Test
    @DisplayName("灵敏度反证：不给这条规则，同一个解会被接受")
    void 单日单科上限_没有规则时违反解被接受() {
        ScheduleInput input = TestFixtures.school8();
        long subjectId = 1L;
        CompiledModel compiled = OrbitSolver.compile(input, List.of());
        var ctx = compiled.context();

        long classId = input.classes().get(0).id();
        int day = pickDayWithTwoFreeSlots(compiled, classId, subjectId);
        forceTwoOnSameDay(compiled, classId, subjectId, day);

        assertNotEquals(CpSolverStatus.INFEASIBLE,
                OrbitSolver.solve(compiled, TestFixtures.quickSolve()).status(),
                "没有这条规则时同一排法应当可行 —— 证明上面的测试确实在测规则本身");
    }

    // ============================================================ 可配置软规则

    @Test
    @DisplayName("单科不连排（软）：目标值确实反映了违规")
    void 软约束的惩罚被计入目标() {
        ScheduleInput input = TestFixtures.school8();
        long classId = input.classes().get(0).id();
        long subjectId = 1L;

        // A：带软规则，强制连排
        CompiledModel withRule = OrbitSolver.compile(input, List.of(
                ConstraintRule.soft(
                        com.orbit.solver.core.rule.handler.SubjectNoConsecutiveHandler.TYPE,
                        RuleParams.of(Map.of("subject_id", subjectId)), 1, "不连排")));
        int day = pickDayWithTwoFreeSlots(withRule, classId, subjectId);
        forceTwoOnSameDay(withRule, classId, subjectId, day);
        SolveOutcome outcomeWith = OrbitSolver.solve(withRule, TestFixtures.quickSolve());

        // B：不带软规则，同样强制连排
        CompiledModel withoutRule = OrbitSolver.compile(input, List.of());
        forceTwoOnSameDay(withoutRule, classId, subjectId, day);
        SolveOutcome outcomeWithout = OrbitSolver.solve(withoutRule, TestFixtures.quickSolve());

        assertTrue(outcomeWith.hasSolution() && outcomeWithout.hasSolution(),
                "软约束不应导致无解");
        assertTrue(outcomeWith.objectiveValue() > outcomeWithout.objectiveValue(),
                "带软约束时目标值应更高（惩罚被计入）：with=" + outcomeWith.objectiveValue()
                        + " without=" + outcomeWithout.objectiveValue());
    }

    // ============================================================ 工具

    private static int pickDayWithTwoFreeSlots(CompiledModel compiled, long classId, long subjectId) {
        var ctx = compiled.context();
        for (int day = 0; day < ctx.days(); day++) {
            if (ctx.freeSubjectSlotsOnDay(classId, subjectId, day).size() >= 2) {
                return day;
            }
        }
        throw new AssertionError("找不到有两个空闲时段的日期：class=" + classId
                + " subject=" + subjectId);
    }

    private static void forceTwoOnSameDay(CompiledModel compiled, long classId,
                                          long subjectId, int day) {
        List<BoolVar> vars = compiled.context().freeSubjectSlotsOnDay(classId, subjectId, day);
        assertTrue(vars.size() >= 2, "该天需要至少两个可用时段");
        compiled.model().addEquality(vars.get(0), 1);
        compiled.model().addEquality(vars.get(1), 1);
    }

    private static ScheduleInput withTeacherLimit(ScheduleInput input, long teacherId, int limit) {
        List<ScheduleInput.TeacherInfo> teachers = input.teachers().stream()
                .map(t -> t.id() == teacherId
                        ? new ScheduleInput.TeacherInfo(t.id(), t.name(), t.subjectId(), limit)
                        : t)
                .toList();
        return new ScheduleInput(input.organizationId(), input.grades(), input.classes(),
                input.subjects(), teachers, input.assignments(), input.gradeSubjects(),
                input.gradeFixedSlots());
    }
}
