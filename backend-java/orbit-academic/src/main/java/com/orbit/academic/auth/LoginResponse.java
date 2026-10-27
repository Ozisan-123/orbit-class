package com.orbit.academic.auth;

import com.orbit.academic.user.UserResponse;

/**
 * 登录响应体。
 *
 * <p>对应 Python {@code routers/auth.py:28}：
 * <pre>{@code
 * return {"access_token": create_token(user), "token_type": "bearer", "user": UserOut.model_validate(user)}
 * }</pre>
 *
 * <p>字段名靠全局 {@code SNAKE_CASE} 策略自动变成 {@code access_token} / {@code token_type} ✓
 * —— 所以这里写 camelCase 是对的，不要手工贴 {@code @JsonProperty}。
 */
public record LoginResponse(String accessToken, String tokenType, UserResponse user) {

    public static LoginResponse of(String accessToken, UserResponse user) {
        return new LoginResponse(accessToken, "bearer", user);
    }
}
