package com.orbit.academic.user;

import jakarta.validation.constraints.NotNull;

/**
 * 新建账号 —— 对应 Python 的 {@code UserCreate}。
 *
 * <pre>{@code
 * class UserCreate(BaseModel):
 *     username: str
 *     display_name: str
 *     password: str
 *     role: str = "operator"
 * }</pre>
 *
 * <p>前三个必填（缺 → 422）；{@code role} 可省，默认 {@code operator}。
 * 注意 {@code username} 在 Python 里是「必填但可为空字符串」——
 * 空串会在 Service 层变成 400 {@code 用户名不能为空}，不是 422。
 * 所以这里用 {@code @NotNull} 而不是 {@code @NotBlank}。
 */
public record UserCreateRequest(
        @NotNull(message = "Field required") String username,
        @NotNull(message = "Field required") String displayName,
        @NotNull(message = "Field required") String password,
        String role) {
}
