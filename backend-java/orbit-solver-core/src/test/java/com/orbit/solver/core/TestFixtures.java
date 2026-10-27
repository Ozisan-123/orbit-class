package com.orbit.solver.core;

import com.orbit.solver.core.engine.SolveOutcome;
import com.orbit.solver.core.engine.SolverSettings;
import com.orbit.solver.core.model.ScheduleInput;
import com.orbit.solver.core.model.TimetableEntry;
import com.orbit.solver.core.rule.ConstraintRule;
import com.orbit.solver.core.rule.RuleParams;
import com.orbit.solver.core.rule.handler.SubjectMaxPerDayHandler;
import com.orbit.solver.core.rule.handler.SubjectNoConsecutiveHandler;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** 测试共用的夹具与断言辅助。 */
public final class TestFixtures {

    /**
     * 与现有 Python 行为等价的一套软规则，权重各为 1。
     *
     * <p>⚠ 定义已经搬到 **生产代码** 的 {@link com.orbit.solver.core.compile.RuleSets#legacyEquivalent()} ——
     * 因为迁移期的 scheduling 服务求解时也必须用同一套规则。
     * 这里只做转发：**只有一份定义**，测试与生产不可能漂移。
     */
    public static List<ConstraintRule> legacyEquivalentRules() {
        return com.orbit.solver.core.compile.RuleSets.legacyEquivalent();
    }

    public static ScheduleInput school8() {
        try {
            return FixtureLoader.loadResource(FixtureLoader.SCHOOL_8);
        } catch (IOException e) {
            throw new IllegalStateException("加载 fixture 失败", e);
        }
    }

    /** 求解用的小超时：这些模型都很小，20 秒足够证明不可行。 */
    public static SolverSettings quickSolve() {
        return new SolverSettings(20, 1, false);
    }

    // ---------------------------------------------------------------- 断言辅助

    /** 统计课表里「某班某科」出现几次。 */
    public static Map<Long, Integer> subjectCountOfClass(List<TimetableEntry> entries, long classId) {
        Map<Long, Integer> counts = new HashMap<>();
        for (TimetableEntry e : entries) {
            if (e.classId() == classId) {
                counts.merge(e.subjectId(), 1, Integer::sum);
            }
        }
        return counts;
    }

    /** 校验课表是否满足「不变量」部分，返回问题描述（空 = 通过）。 */
    public static String checkInvariants(ScheduleInput input, List<TimetableEntry> entries) {
        StringBuilder problems = new StringBuilder();

        // 1) 同一教师同一时段不能上两个班
        Map<String, TimetableEntry> byTeacherSlot = new HashMap<>();
        for (TimetableEntry e : entries) {
            String key = e.teacherId() + "@" + e.dayOfWeek() + "-" + e.periodIndex();
            TimetableEntry previous = byTeacherSlot.put(key, e);
            if (previous != null) {
                problems.append("教师冲突: teacher=").append(e.teacherId())
                        .append(" day=").append(e.dayOfWeek()).append(" period=").append(e.periodIndex())
                        .append(" 同时被 class=").append(previous.classId())
                        .append(" 和 class=").append(e.classId()).append(" 占用; ");
            }
        }

        // 2) 同一班同一时段只能有一节课
        Map<String, TimetableEntry> byClassSlot = new HashMap<>();
        for (TimetableEntry e : entries) {
            String key = e.classId() + "@" + e.dayOfWeek() + "-" + e.periodIndex();
            if (byClassSlot.put(key, e) != null) {
                problems.append("班级时段重复: class=").append(e.classId())
                        .append(" day=").append(e.dayOfWeek()).append(" period=").append(e.periodIndex())
                        .append("; ");
            }
        }

        // 3) 各班各科节数必须等于 grade_subject.weekly_hours
        Map<Long, Long> gradeOfClass = new HashMap<>();
        input.classes().forEach(c -> gradeOfClass.put(c.id(), c.gradeId()));
        Map<String, Integer> required = new HashMap<>();
        for (ScheduleInput.GradeSubjectInfo gs : input.gradeSubjects()) {
            required.put(gs.gradeId() + "/" + gs.subjectId(), gs.weeklyHours());
        }
        for (ScheduleInput.ClassInfo c : input.classes()) {
            Map<Long, Integer> actual = subjectCountOfClass(entries, c.id());
            for (Map.Entry<String, Integer> req : required.entrySet()) {
                String[] parts = req.getKey().split("/");
                if (Long.parseLong(parts[0]) != c.gradeId()) {
                    continue;
                }
                long subjectId = Long.parseLong(parts[1]);
                int got = actual.getOrDefault(subjectId, 0);
                if (got != req.getValue()) {
                    problems.append("周课时不符: class=").append(c.id())
                            .append(" subject=").append(subjectId)
                            .append(" 期望=").append(req.getValue())
                            .append(" 实际=").append(got).append("; ");
                }
            }
        }

        // 4) 每节课都必须是该班真实存在的任教关系
        Map<String, Boolean> assignmentExists = new HashMap<>();
        for (ScheduleInput.Assignment a : input.assignments()) {
            assignmentExists.put(a.classId() + "/" + a.subjectId() + "/" + a.teacherId(), true);
        }
        for (TimetableEntry e : entries) {
            if (!assignmentExists.containsKey(e.classId() + "/" + e.subjectId() + "/" + e.teacherId())) {
                problems.append("不存在的任教关系: ").append(e.classId()).append("/")
                        .append(e.subjectId()).append("/").append(e.teacherId()).append("; ");
            }
        }

        return problems.toString();
    }

    public static boolean hasSolution(SolveOutcome outcome) {
        return !outcome.entries().isEmpty();
    }

    // ---------------------------------------------------------------- 独立校验器
    // 下面这几个方法刻意不复用建模代码，而是从「排出来的课表」反推指标。
    // 这样它们才能独立验证建模是否真的表达了想要的语义（包括边界：当天没课、
    // 各班节数不同、末节不算空档）。

    /** 每个班每天几节。 */
    public static Map<Long, int[]> classPeriods(ScheduleInput input) {
        Map<Long, int[]> gradePeriods = new HashMap<>();
        for (ScheduleInput.GradeInfo g : input.grades()) {
            int[] perDay = new int[5];
            int i = 0;
            for (int value : g.periodsPerDay().values()) {
                if (i < 5) {
                    perDay[i++] = value;
                }
            }
            gradePeriods.put(g.id(), perDay);
        }
        Map<Long, int[]> result = new HashMap<>();
        for (ScheduleInput.ClassInfo c : input.classes()) {
            result.put(c.id(), gradePeriods.getOrDefault(c.gradeId(), new int[5]));
        }
        return result;
    }

    /** 独立统计教师空档数：某教师某天「首节课到末节课之间」的空闲节数。 */
    public static int countTeacherGaps(List<TimetableEntry> entries) {
        Map<String, TreeSet<Integer>> byTeacherDay = new HashMap<>();
        for (TimetableEntry e : entries) {
            byTeacherDay
                    .computeIfAbsent(e.teacherId() + "@" + e.dayOfWeek(), k -> new TreeSet<>())
                    .add(e.periodIndex());
        }
        int gaps = 0;
        for (TreeSet<Integer> periods : byTeacherDay.values()) {
            if (periods.size() <= 1) {
                continue;       // 一天最多一节 → 不可能有空档
            }
            gaps += (periods.last() - periods.first() + 1) - periods.size();
        }
        return gaps;
    }

    /** 独立统计「某科目被排在当天最后一节」的次数。 */
    public static int countSubjectAtLastPeriod(List<TimetableEntry> entries, ScheduleInput input,
                                               long subjectId) {
        Map<Long, int[]> periods = classPeriods(input);
        int count = 0;
        for (TimetableEntry e : entries) {
            int[] perDay = periods.get(e.classId());
            if (perDay == null || perDay[e.dayOfWeek()] <= 0) {
                continue;
            }
            if (e.subjectId() == subjectId
                    && e.periodIndex() == perDay[e.dayOfWeek()] - 1) {
                count++;
            }
        }
        return count;
    }

    /** 独立统计「某科目被排在第 morningPeriods 节之后」的次数。 */
    public static int countSubjectAfterPeriod(List<TimetableEntry> entries, ScheduleInput input,
                                              long subjectId, int morningPeriods) {
        int count = 0;
        for (TimetableEntry e : entries) {
            if (e.subjectId() == subjectId && e.periodIndex() >= morningPeriods) {
                count++;
            }
        }
        return count;
    }

    /** 独立统计「某教师在某天某节有课」的次数。 */
    public static int countTeacherScheduledAt(List<TimetableEntry> entries, long teacherId,
                                              int day, int period) {
        int count = 0;
        for (TimetableEntry e : entries) {
            if (e.teacherId() == teacherId && e.dayOfWeek() == day && e.periodIndex() == period) {
                count++;
            }
        }
        return count;
    }

    /** 独立统计某教师某天的课时数。 */
    public static int countTeacherLessonsOnDay(List<TimetableEntry> entries,
                                               long teacherId, int day) {
        int count = 0;
        for (TimetableEntry e : entries) {
            if (e.teacherId() == teacherId && e.dayOfWeek() == day) {
                count++;
            }
        }
        return count;
    }

    private TestFixtures() {
    }
}
