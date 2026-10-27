package com.orbit.scheduling.diagnosis;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.orbit.scheduling.snapshot.AssignmentRef;
import com.orbit.scheduling.snapshot.ClassRef;
import com.orbit.scheduling.snapshot.GradeFixedSlotRef;
import com.orbit.scheduling.snapshot.GradeRef;
import com.orbit.scheduling.snapshot.GradeSubjectRef;
import com.orbit.scheduling.snapshot.SnapshotMappers;
import com.orbit.scheduling.snapshot.SubjectRef;
import com.orbit.scheduling.snapshot.TeacherRef;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 静态诊断 —— **不求解**就能判定"一定排不出来"的检查。
 *
 * <h2>为什么它比"最小冲突集"更值得先做</h2>
 * 真实世界里"排不出来"绝大多数是**数据问题**，不是约束组合太紧：
 * <ul>
 *   <li>某个班的周课时加起来超过了它一周的座位数</li>
 *   <li>某位教师被分配的课时超过了他自己的上限</li>
 *   <li>某个班某门课**根本没配老师**（于是"周课时必须排满"这条不变量永远无法满足）</li>
 *   <li>固定课自相矛盾（同一班同一节固定了两门课，或把老师固定到两个班）</li>
 * </ul>
 * 这四类都能在**毫秒级**算出来，而且给出的结论是**确定的**（不是"可能"）。
 * 相比之下最小冲突集要对模型做松弛再求解，贵得多，应该只在静态检查一无所获时才用。
 *
 * <p>⚠ 这也是为什么诊断要**先静态、后求解**：先花 1 毫秒排除 90% 的可能，
 * 再花几十秒去分析剩下 10%。
 */
@Component
public class StaticDiagnostics {

    /** 一周的上课日 —— 与引擎一致（周一到周五）。 */
    private static final List<String> DAY_KEYS = List.of("Mon", "Tue", "Wed", "Thu", "Fri");

    private final SnapshotMappers.GradeRefMapper gradeMapper;
    private final SnapshotMappers.ClassRefMapper classMapper;
    private final SnapshotMappers.SubjectRefMapper subjectMapper;
    private final SnapshotMappers.TeacherRefMapper teacherMapper;
    private final SnapshotMappers.GradeSubjectRefMapper gradeSubjectMapper;
    private final SnapshotMappers.AssignmentRefMapper assignmentMapper;
    private final SnapshotMappers.GradeFixedSlotRefMapper gradeFixedSlotMapper;

    public StaticDiagnostics(SnapshotMappers.GradeRefMapper gradeMapper,
                             SnapshotMappers.ClassRefMapper classMapper,
                             SnapshotMappers.SubjectRefMapper subjectMapper,
                             SnapshotMappers.TeacherRefMapper teacherMapper,
                             SnapshotMappers.GradeSubjectRefMapper gradeSubjectMapper,
                             SnapshotMappers.AssignmentRefMapper assignmentMapper,
                             SnapshotMappers.GradeFixedSlotRefMapper gradeFixedSlotMapper) {
        this.gradeMapper = gradeMapper;
        this.classMapper = classMapper;
        this.subjectMapper = subjectMapper;
        this.teacherMapper = teacherMapper;
        this.gradeSubjectMapper = gradeSubjectMapper;
        this.assignmentMapper = assignmentMapper;
        this.gradeFixedSlotMapper = gradeFixedSlotMapper;
    }

    /** 跑完全部静态检查；返回空列表 = 静态层面没发现"一定排不出来"的问题。 */
    public List<DiagnosisFinding> run(long organizationId) {
        List<DiagnosisFinding> findings = new ArrayList<>();

        Map<Long, GradeRef> grades = byId(gradeMapper.selectList(
                Wrappers.<GradeRef>lambdaQuery().eq(GradeRef::getOrganizationId, organizationId)),
                GradeRef::getId);
        Map<Long, ClassRef> classes = byId(classMapper.selectList(
                Wrappers.<ClassRef>lambdaQuery().eq(ClassRef::getOrganizationId, organizationId)),
                ClassRef::getId);
        Map<Long, SubjectRef> subjects = byId(subjectMapper.selectList(
                Wrappers.<SubjectRef>lambdaQuery().eq(SubjectRef::getOrganizationId, organizationId)),
                SubjectRef::getId);
        List<TeacherRef> teachers = teacherMapper.selectList(
                Wrappers.<TeacherRef>lambdaQuery().eq(TeacherRef::getOrganizationId, organizationId));
        List<GradeSubjectRef> gradeSubjects = gradeSubjectMapper.selectList(
                Wrappers.<GradeSubjectRef>lambdaQuery().eq(GradeSubjectRef::getOrganizationId, organizationId));
        List<AssignmentRef> assignments = assignmentMapper.selectList(
                Wrappers.<AssignmentRef>lambdaQuery().eq(AssignmentRef::getOrganizationId, organizationId));
        List<GradeFixedSlotRef> fixedSlots = gradeFixedSlotMapper.selectList(
                Wrappers.<GradeFixedSlotRef>lambdaQuery().eq(GradeFixedSlotRef::getOrganizationId, organizationId));

        checkMissingAssignments(findings, classes, subjects, gradeSubjects, assignments);
        checkClassCapacity(findings, classes, grades, gradeSubjects);
        checkTeacherOverload(findings, classes, teachers, gradeSubjects, assignments);
        checkFixedSlots(findings, grades, classes, subjects, fixedSlots);
        return findings;
    }

    // ---------------------------------------------------------------- ①②③④

    /**
     * 某个班某门课有周课时要求，却**没有任何教师**能上。
     *
     * <p>后果是确定的：不变量「周课时必须排满」永远无法满足 → 一定无解。
     * 这条在真实数据里非常常见（新加了一门课忘了配老师）。
     */
    private void checkMissingAssignments(List<DiagnosisFinding> findings,
                                         Map<Long, ClassRef> classes,
                                         Map<Long, SubjectRef> subjects,
                                         List<GradeSubjectRef> gradeSubjects,
                                         List<AssignmentRef> assignments) {
        Map<String, Boolean> assigned = new HashMap<>();
        for (AssignmentRef a : assignments) {
            assigned.put(a.getClassId() + ":" + a.getSubjectId(), Boolean.TRUE);
        }
        // 按班聚合它的周课时要求
        Map<Long, List<GradeSubjectRef>> byGrade = new HashMap<>();
        for (GradeSubjectRef gs : gradeSubjects) {
            byGrade.computeIfAbsent(gs.getGradeId(), k -> new ArrayList<>()).add(gs);
        }
        for (ClassRef clazz : classes.values()) {
            for (GradeSubjectRef gs : byGrade.getOrDefault(clazz.getGradeId(), List.of())) {
                if (gs.getWeeklyHours() == null || gs.getWeeklyHours() <= 0) {
                    continue;
                }
                if (assigned.containsKey(clazz.getId() + ":" + gs.getSubjectId())) {
                    continue;
                }
                String subjectName = nameOf(subjects, gs.getSubjectId());
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("class_id", clazz.getId());
                payload.put("class_name", clazz.getName());
                payload.put("subject_id", gs.getSubjectId());
                payload.put("subject_name", subjectName);
                payload.put("weekly_hours", gs.getWeeklyHours());
                findings.add(new DiagnosisFinding("MISSING_ASSIGNMENT", DiagnosisFinding.ERROR,
                        clazz.getName() + "的「" + subjectName + "」每周要上 " + gs.getWeeklyHours()
                                + " 节，但没有分配任何教师 —— 这节课永远排不出来",
                        payload));
            }
        }
    }

    /**
     * 一个班一周的座位数装不下它的周课时总量。
     *
     * <p>算法：{@code Σ 各天的 periods_per_day} 对 {@code Σ grade_subjects.weekly_hours}。
     * 注意这里**不扣固定课** —— 固定课占的也是座位，所以直接比总量就是正确的上界判断。
     */
    private void checkClassCapacity(List<DiagnosisFinding> findings,
                                    Map<Long, ClassRef> classes,
                                    Map<Long, GradeRef> grades,
                                    List<GradeSubjectRef> gradeSubjects) {
        Map<Long, Integer> requiredByGrade = new HashMap<>();
        for (GradeSubjectRef gs : gradeSubjects) {
            int hours = gs.getWeeklyHours() == null ? 0 : gs.getWeeklyHours();
            requiredByGrade.merge(gs.getGradeId(), hours, Integer::sum);
        }
        for (ClassRef clazz : classes.values()) {
            GradeRef grade = grades.get(clazz.getGradeId());
            if (grade == null) {
                continue;
            }
            int capacity = 0;
            for (String day : DAY_KEYS) {
                Integer periods = grade.getPeriodsPerDay() == null
                        ? null : grade.getPeriodsPerDay().get(day);
                capacity += periods == null ? 0 : periods;
            }
            int required = requiredByGrade.getOrDefault(clazz.getGradeId(), 0);
            if (required > capacity) {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("class_id", clazz.getId());
                payload.put("class_name", clazz.getName());
                payload.put("required_hours", required);
                payload.put("capacity", capacity);
                payload.put("shortfall", required - capacity);
                findings.add(new DiagnosisFinding("CLASS_CAPACITY_SHORTFALL", DiagnosisFinding.ERROR,
                        clazz.getName() + "一周要排 " + required + " 节课，但只有 " + capacity
                                + " 个时段，差了 " + (required - capacity) + " 节 —— 需要增加每天的节数或减少周课时",
                        payload));
            }
        }
    }

    /**
     * 教师被分配的课时超过了他自己的上限（{@code teachers.max_weekly_hours}）。
     *
     * <p>这正是方案 §6.4 举的那个例子。它是**数据驱动**的硬约束
     * （`teacher_max_weekly_hours`，不变量层），所以用户改不了它、只能改数据。
     *
     * <p>算法：这位教师教的每个 {@code (班, 科)} 对应的 {@code grade_subjects.weekly_hours} 求和。
     * 注意同一个 {@code (班,科)} 若有多位教师，课时是**共同分担**而不是各自全担 ——
     * 所以这里只在"该 (班,科) 只有这一位教师"时才计入，避免把正常情况误报成超载。
     */
    private void checkTeacherOverload(List<DiagnosisFinding> findings,
                                      Map<Long, ClassRef> classes,
                                      List<TeacherRef> teachers,
                                      List<GradeSubjectRef> gradeSubjects,
                                      List<AssignmentRef> assignments) {
        Map<String, Integer> weeklyHours = new HashMap<>();
        for (GradeSubjectRef gs : gradeSubjects) {
            weeklyHours.put(gs.getGradeId() + ":" + gs.getSubjectId(),
                    gs.getWeeklyHours() == null ? 0 : gs.getWeeklyHours());
        }
        // (班,科) → 教师数，用来判断课时是否独担
        Map<String, Integer> teacherCount = new HashMap<>();
        for (AssignmentRef a : assignments) {
            teacherCount.merge(a.getClassId() + ":" + a.getSubjectId(), 1, Integer::sum);
        }

        for (TeacherRef teacher : teachers) {
            int assigned = 0;
            for (AssignmentRef a : assignments) {
                if (!java.util.Objects.equals(a.getTeacherId(), teacher.getId())) {
                    continue;
                }
                if (teacherCount.getOrDefault(a.getClassId() + ":" + a.getSubjectId(), 0) != 1) {
                    continue;   // 多师分担，不能按全量计
                }
                ClassRef clazz = classes.get(a.getClassId());
                if (clazz == null) {
                    continue;
                }
                assigned += weeklyHours.getOrDefault(clazz.getGradeId() + ":" + a.getSubjectId(), 0);
            }
            int limit = teacher.getMaxWeeklyHours() == null ? 0 : teacher.getMaxWeeklyHours();
            if (limit > 0 && assigned > limit) {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("teacher_id", teacher.getId());
                payload.put("teacher_name", teacher.getName());
                payload.put("actual", assigned);
                payload.put("limit", limit);
                payload.put("excess", assigned - limit);
                findings.add(new DiagnosisFinding("TEACHER_OVERLOAD", DiagnosisFinding.ERROR,
                        teacher.getName() + "这学期被分配了 " + assigned + " 节课，上限是 " + limit
                                + " 节，超了 " + (assigned - limit)
                                + " 节 —— 可以减少一个班，或把上限调到 " + assigned + " 节",
                        payload));
            }
        }
    }

    /**
     * 固定课自相矛盾或越界。
     *
     * <p>三种情况：
     * <ol>
     *   <li>同一**年级**同一节固定了两门不同的课 → 该年级的班无法同时满足</li>
     *   <li>固定到了当天不存在的节次（例如某年级周五只有 4 节，却固定在第 5 节）</li>
     *   <li>把同一位教师固定到同一节的两个年级 → 教师撞课（不变量）</li>
     * </ol>
     */
    private void checkFixedSlots(List<DiagnosisFinding> findings,
                                 Map<Long, GradeRef> grades,
                                 Map<Long, ClassRef> classes,
                                 Map<Long, SubjectRef> subjects,
                                 List<GradeFixedSlotRef> fixedSlots) {
        // ① 同年级同节多门课
        Map<String, List<GradeFixedSlotRef>> byGradeSlot = new TreeMap<>();
        for (GradeFixedSlotRef slot : fixedSlots) {
            byGradeSlot.computeIfAbsent(slot.getGradeId() + ":" + slot.getDayOfWeek()
                    + ":" + slot.getPeriodIndex(), k -> new ArrayList<>()).add(slot);
        }
        for (Map.Entry<String, List<GradeFixedSlotRef>> entry : byGradeSlot.entrySet()) {
            List<GradeFixedSlotRef> group = entry.getValue();
            long distinctSubjects = group.stream().map(GradeFixedSlotRef::getSubjectId).distinct().count();
            if (distinctSubjects > 1) {
                GradeFixedSlotRef first = group.get(0);
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("grade_id", first.getGradeId());
                payload.put("grade_name", nameOfGrade(grades, first.getGradeId()));
                payload.put("day_of_week", first.getDayOfWeek());
                payload.put("period_index", first.getPeriodIndex());
                payload.put("subject_ids", group.stream().map(GradeFixedSlotRef::getSubjectId).distinct().toList());
                findings.add(new DiagnosisFinding("FIXED_SLOT_CONFLICT", DiagnosisFinding.ERROR,
                        nameOfGrade(grades, first.getGradeId()) + "在" + dayName(first.getDayOfWeek())
                                + "第 " + (first.getPeriodIndex() + 1) + " 节固定了 "
                                + distinctSubjects + " 门不同的课 —— 一个班同一节只能上一门课",
                        payload));
            }
        }
        // ② 越界
        for (GradeFixedSlotRef slot : fixedSlots) {
            GradeRef grade = grades.get(slot.getGradeId());
            if (grade != null && grade.getPeriodsPerDay() != null
                    && slot.getDayOfWeek() != null && slot.getDayOfWeek() >= 0
                    && slot.getDayOfWeek() < DAY_KEYS.size() && slot.getPeriodIndex() != null) {
                Integer periods = grade.getPeriodsPerDay().get(DAY_KEYS.get(slot.getDayOfWeek()));
                if (periods != null && slot.getPeriodIndex() >= periods) {
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("grade_id", slot.getGradeId());
                    payload.put("grade_name", grade.getName());
                    payload.put("day_of_week", slot.getDayOfWeek());
                    payload.put("period_index", slot.getPeriodIndex());
                    payload.put("periods_on_day", periods);
                    findings.add(new DiagnosisFinding("FIXED_SLOT_OUT_OF_RANGE", DiagnosisFinding.ERROR,
                            grade.getName() + "在" + dayName(slot.getDayOfWeek()) + "只有 " + periods
                                    + " 节，却把固定课放在第 " + (slot.getPeriodIndex() + 1) + " 节",
                            payload));
                }
            }
        }
        // ③ 同一节被多个年级固定。
        //    ⚠ 只报 warning，不报 error：不同年级完全可以有不同的老师，所以这只说明
        //      "**有可能**撞课"。要精确判定必须把 (年级,科目) 展开到具体教师 ——
        //      那属于最小冲突集分析的范畴。**宁可给一个措辞准确的警告，
        //      也不要给一个看起来很确定、但其实是猜的结论。**
        Map<String, java.util.Set<Long>> gradesBySlot = new TreeMap<>();
        for (GradeFixedSlotRef slot : fixedSlots) {
            gradesBySlot.computeIfAbsent(slot.getDayOfWeek() + ":" + slot.getPeriodIndex(),
                    k -> new java.util.HashSet<>()).add(slot.getGradeId());
        }
        for (Map.Entry<String, java.util.Set<Long>> entry : gradesBySlot.entrySet()) {
            if (entry.getValue().size() > 1) {
                String[] parts = entry.getKey().split(":");
                int day = Integer.parseInt(parts[0]);
                int period = Integer.parseInt(parts[1]);
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("day_of_week", day);
                payload.put("period_index", period);
                payload.put("grade_ids", List.copyOf(entry.getValue()));
                findings.add(new DiagnosisFinding("FIXED_SLOT_SHARED_SLOT", DiagnosisFinding.WARNING,
                        dayName(day) + "第 " + (period + 1) + " 节被 " + entry.getValue().size()
                                + " 个年级同时固定 —— 若这些课是同一位老师，就会撞课",
                        payload));
            }
        }
    }

    // ---------------------------------------------------------------- 小工具

    private static String dayName(int day) {
        return day >= 0 && day < DAY_KEYS.size() ? "周" + "一二三四五".charAt(day) : ("第" + day + "天");
    }

    private String nameOfGrade(Map<Long, GradeRef> grades, Long id) {
        GradeRef grade = grades.get(id);
        return grade == null ? ("年级" + id) : grade.getName();
    }

    private static String nameOf(Map<Long, SubjectRef> subjects, Long id) {
        SubjectRef subject = subjects.get(id);
        return subject == null ? ("科目" + id) : subject.getName();
    }

    private static <T> Map<Long, T> byId(List<T> rows, java.util.function.Function<T, Long> idOf) {
        Map<Long, T> out = new HashMap<>();
        for (T row : rows) {
            out.put(idOf.apply(row), row);
        }
        return out;
    }
}
