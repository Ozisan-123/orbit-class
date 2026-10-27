package com.orbit.academic.teacher;

import jakarta.validation.constraints.NotNull;

/**
 * 新建教师 —— 对应 Python 的 {@code TeacherCreate}。
 *
 * <pre>{@code
 * class TeacherCreate(BaseModel):
 *     name: str
 *     subject_id: int
 *     max_weekly_hours: int = 18
 * }</pre>
 *
 * <p>默认 18 在 Service 里落实。{@code subjectId} 用包装类型（缺字段时会传 null）。
 */
public record TeacherCreateRequest(
        @NotNull(message = "Field required") String name,
        @NotNull(message = "Field required") Long subjectId,
        Integer maxWeeklyHours) {
}
