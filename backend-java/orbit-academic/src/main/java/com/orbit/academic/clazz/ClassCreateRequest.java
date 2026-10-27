package com.orbit.academic.clazz;

import jakarta.validation.constraints.NotNull;

/**
 * 新建班级 —— 对应 Python 的 {@code ClassCreate}：{@code {name: str, grade_id: int}}。
 *
 * <p>两个字段都必填（缺了 422）。{@code gradeId} 用 {@code Long} 而不是 {@code long}：
 * 缺字段时 Spring 会传 null，原始类型接不住（H1 那个坑）。
 */
public record ClassCreateRequest(
        @NotNull(message = "Field required") String name,
        @NotNull(message = "Field required") Long gradeId) {
}
