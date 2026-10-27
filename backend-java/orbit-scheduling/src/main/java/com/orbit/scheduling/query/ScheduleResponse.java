package com.orbit.scheduling.query;

/**
 * 一条课表记录 —— 对应 Python 的 {@code ScheduleOut}。
 *
 * <p>⚠ {@code subject_name} / {@code teacher_name} / {@code class_name} 在 Python 里
 * 是用 {@code .get(id, "")} 取的 —— **找不到时是空字符串，不是 null**。
 * 这个区别对前端是可见的（空串能渲染、null 会显示 undefined），所以这里也用空串。
 */
public record ScheduleResponse(Long id, Long classId, Long subjectId, Long teacherId,
                               Integer dayOfWeek, Integer periodIndex,
                               String subjectName, String teacherName, String className) {
}
