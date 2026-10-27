package com.orbit.academic.fixedslot;

import jakarta.validation.constraints.NotNull;

/** 新建年级固定课 —— 对应 Python {@code GradeFixedSlotCreate}：四个字段都必填。 */
public record GradeFixedSlotCreateRequest(
        @NotNull(message = "Field required") Long gradeId,
        @NotNull(message = "Field required") Long subjectId,
        @NotNull(message = "Field required") Integer dayOfWeek,
        @NotNull(message = "Field required") Integer periodIndex) {
}
