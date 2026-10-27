package com.orbit.academic.assignment;

import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 设置某教师负责的班级 —— 对应 Python {@code TeacherClassAssignmentsUpdate}。
 *
 * <p>{@code classIds} 是必填字段（Python 里没有默认值）；传空列表表示**清空该教师的所有班级**。
 * 这个语义很关键：空列表 ≠ 不改动。
 */
public record TeacherClassAssignmentsRequest(
        @NotNull(message = "Field required") List<Long> classIds) {
}
