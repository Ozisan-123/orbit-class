package com.orbit.academic.subject;

import jakarta.validation.constraints.NotNull;

import java.util.Map;

/**
 * 新增科目请求体 —— 对应 Python 的 {@code SubjectCreate}。
 *
 * <pre>{@code
 * class SubjectCreate(BaseModel):
 *     name: str                                            # 必填
 *     category: str = "minor"                              # 可省，默认 minor
 *     weekly_hours_by_grade: dict[int, int] = {}           # 可省，默认空
 * }</pre>
 *
 * <p>所以只有 {@code name} 用 {@link NotNull}（缺了就 422），
 * 另外两个是可选的 —— {@code category} 省略时按 {@code "minor"} 处理，
 * {@code weeklyHoursByGrade} 省略时按空处理。这两个默认值在 Service 里落实，
 * 不放在 DTO 上，避免「默认值散落在两处」。
 *
 * <p>{@code weeklyHoursByGrade} 的 JSON 键是字符串，Jackson 会自动转成 {@link Long}
 * 的 Map 键 ✓（Python 那边是 {@code dict[int, int]}，Pydantic 也做同样的转换）。
 */
public record SubjectCreateRequest(
        @NotNull(message = "Field required") String name,
        String category,
        Map<Long, Integer> weeklyHoursByGrade) {
}
