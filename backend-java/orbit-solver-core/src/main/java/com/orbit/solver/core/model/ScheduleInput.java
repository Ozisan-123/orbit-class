package com.orbit.solver.core.model;

import java.util.List;
import java.util.Map;

/**
 * 一次排课的全部输入 —— 对标 Python 侧 {@code generate_schedule} 开头那 7 条查询。
 *
 * <p>spike 阶段直接从 JSON fixture 读入；正式实现里由 scheduling 服务拉取 academic 的快照后组装，
 * 因此这里刻意不带任何持久化注解。
 */
public record ScheduleInput(
        long organizationId,
        List<GradeInfo> grades,
        List<ClassInfo> classes,
        List<SubjectInfo> subjects,
        List<TeacherInfo> teachers,
        List<Assignment> assignments,
        List<GradeSubjectInfo> gradeSubjects,
        List<FixedSlotRule> gradeFixedSlots) {

    /** 年级，含各班每天节数配置（对应 models.Grade.periods_per_day）。 */
    public record GradeInfo(long id, String name, Map<String, Integer> periodsPerDay) {
    }

    public record ClassInfo(long id, String name, long gradeId) {
    }

    public record SubjectInfo(long id, String name, String category) {
    }

    /** maxWeeklyHours 对应 models.Teacher.max_weekly_hours。 */
    public record TeacherInfo(long id, String name, long subjectId, int maxWeeklyHours) {
    }

    /**
     * 任教关系。
     *
     * <p>字段顺序刻意与 Python 的 {@code all_assigns} 元组一致
     * {@code (class_id, subject_id, teacher_id)}，避免移植时下标错位。
     */
    public record Assignment(long classId, long subjectId, long teacherId) {
    }

    /** 年级科目周课时（对应 models.GradeSubject）。 */
    public record GradeSubjectInfo(long gradeId, long subjectId, int weeklyHours) {
    }

    /** 年级级固定课（对应 models.GradeFixedSlot），由引擎展开到该年级的每个班。 */
    public record FixedSlotRule(long gradeId, long subjectId, int day, int period) {
    }
}
