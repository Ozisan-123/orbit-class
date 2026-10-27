package com.orbit.common.error;

/**
 * 统一错误体：{@code {"detail": "人话"}}。
 *
 * <p>§9 的契约要求：<b>不要用 Spring 默认的 {@code ProblemDetail}</b>
 * （它长这样：{@code {"type":"about:blank","title":"Not Found","status":404,"detail":"...","instance":"/api/x"}}，
 * 前端读 {@code error.response.data.detail} 虽然也能读到，但多出的字段和
 * 不同的 {@code type} 语义会让契约测试很难比对）。
 *
 * <p>Python 侧的 {@code HTTPException} 就是这一个字段，所以这里也只有一个字段。
 */
public record ErrorResponse(String detail) {

    public static ErrorResponse of(String detail) {
        return new ErrorResponse(detail);
    }
}
