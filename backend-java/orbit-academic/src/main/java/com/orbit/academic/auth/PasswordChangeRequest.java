package com.orbit.academic.auth;

import jakarta.validation.constraints.NotNull;

/**
 * 修改密码请求体（Python {@code schemas.py:174}）。
 *
 * <p>两个字段都是「必填但无长度约束」的 {@code str} —— 同 {@link LoginRequest} 的理由，
 * 用 {@link NotNull} 而非 {@code @NotBlank}。
 *
 * <p>「新密码至少 8 位」这条**不在这里校验**，而是由
 * {@code PythonPbkdf2PasswordEncoder.encode} 抛 {@link IllegalArgumentException}，
 * 再被转成 {@code 400 {"detail":"密码至少需要 8 位"}} ——
 * 与 Python 的 {@code hash_password} 抛 {@code ValueError} 后被
 * {@code except ValueError → HTTPException(400, str(exc))} 捕获的行为一致。
 * 放在这里做会变成 422，状态码就不一样了。
 */
public record PasswordChangeRequest(
        @NotNull(message = "Field required") String currentPassword,
        @NotNull(message = "Field required") String newPassword) {
}
