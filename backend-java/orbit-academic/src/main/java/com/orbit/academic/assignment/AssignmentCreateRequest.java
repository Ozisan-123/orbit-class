package com.orbit.academic.assignment;

import jakarta.validation.constraints.NotNull;

/** 新建任教分配 —— 对应 Python {@code AssignmentCreate}：三个 id 都必填。 */
public record AssignmentCreateRequest(
        @NotNull(message = "Field required") Long teacherId,
        @NotNull(message = "Field required") Long classId,
        @NotNull(message = "Field required") Long subjectId) {
}
