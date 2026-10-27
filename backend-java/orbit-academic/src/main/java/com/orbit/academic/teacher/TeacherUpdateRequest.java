package com.orbit.academic.teacher;

/** 修改教师 —— 对应 Python {@code TeacherUpdate}：三个字段都可选，局部更新语义。 */
public record TeacherUpdateRequest(String name, Long subjectId, Integer maxWeeklyHours) {
}
