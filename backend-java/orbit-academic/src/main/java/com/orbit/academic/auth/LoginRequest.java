package com.orbit.academic.auth;

import jakarta.validation.constraints.NotNull;

/**
 * 登录请求体。
 *
 * <p>逐字对应 Python 的 {@code schemas.py:169}：
 * <pre>{@code
 * class LoginRequest(BaseModel):
 *     username: str
 *     password: str
 * }</pre>
 *
 * <p>⚠ <b>用 {@link NotNull} 而不是 {@code @NotBlank}</b>，这不是笔误：
 * Python 的 {@code username: str} 是「必填、但允许空字符串」——
 * <ul>
 *   <li>请求体里**没有** {@code username} 字段 → Pydantic 报 422 {@code Field required}
 *       （实测：{@code {"detail":[{"type":"missing","loc":["body","name"],...}]}}）</li>
 *   <li>{@code "username": ""} → 通过校验，走到查库 → 查无此人 → <b>401</b> {@code 用户名或密码错误}</li>
 * </ul>
 * 用 {@code @NotBlank} 会把第二种情况也变成 422，与 Python 不一致。
 */
public record LoginRequest(
        @NotNull(message = "Field required") String username,
        @NotNull(message = "Field required") String password) {
}
