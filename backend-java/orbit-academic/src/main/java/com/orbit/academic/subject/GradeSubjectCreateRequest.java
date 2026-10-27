package com.orbit.academic.subject;

import jakarta.validation.constraints.NotNull;

/** 新建/更新年级课时 —— 对应 Python {@code GradeSubjectCreate}：三个字段都必填。 */
public record GradeSubjectCreateRequest(
        @NotNull(message = "Field required") Long gradeId,
        @NotNull(message = "Field required") Long subjectId,
        @NotNull(message = "Field required") Integer weeklyHours) {
}
