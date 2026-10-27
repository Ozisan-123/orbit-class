package com.orbit.academic.grade;

import jakarta.validation.constraints.NotNull;

import java.util.Map;

/**
 * 新建年级 —— 对应 Python 的 {@code GradeCreate}。
 *
 * <pre>{@code
 * class GradeCreate(BaseModel):
 *     name: str
 *     periods_per_day: dict = {"Mon": 6, "Tue": 6, "Wed": 6, "Thu": 6, "Fri": 6}
 * }</pre>
 *
 * <p>{@code name} 必填（缺了 422）；{@code periods_per_day} 可省，省略时用默认值
 * —— 默认值在 Service 里落实（{@link GradeService#DEFAULT_PERIODS_PER_DAY}），
 * 不放在 DTO 上，避免"默认值散落两处"。
 */
public record GradeCreateRequest(
        @NotNull(message = "Field required") String name,
        Map<String, Integer> periodsPerDay) {
}
