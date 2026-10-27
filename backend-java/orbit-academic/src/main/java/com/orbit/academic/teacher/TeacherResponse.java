package com.orbit.academic.teacher;

/**
 * 教师视图 —— 对应 Python 的 {@code TeacherOut}。
 *
 * <pre>{@code {"id": 1, "name": "张老师", "subject_id": 2, "max_weekly_hours": 18, "subject_name": "数学"}}</pre>
 */
public record TeacherResponse(Long id, String name, Long subjectId,
                              Integer maxWeeklyHours, String subjectName) {

    public static TeacherResponse of(Teacher teacher, String subjectName) {
        return new TeacherResponse(teacher.getId(), teacher.getName(), teacher.getSubjectId(),
                teacher.getMaxWeeklyHours(), subjectName);
    }
}
