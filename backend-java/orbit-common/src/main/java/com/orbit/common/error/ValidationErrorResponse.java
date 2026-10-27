package com.orbit.common.error;

import java.util.List;

/**
 * 校验失败专用错误体：{@code {"detail": [ ... ]}}。
 *
 * <p>与 {@link ErrorResponse}（{@code {"detail": "字符串"}}）是**两种形状**，
 * 因为 Python 侧本来就是两种 —— 见 {@link ValidationErrorItem} 的说明。
 */
public record ValidationErrorResponse(List<ValidationErrorItem> detail) {

    public ValidationErrorResponse {
        detail = detail == null ? List.of() : List.copyOf(detail);
    }
}
