package com.orbit.academic.user;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDateTime;

/**
 * 对外的用户视图 —— 对应 Python 的 {@code UserOut}（前端 {@code types/index.ts} 的 {@code User}）。
 *
 * <p>实测 Python 的 {@code GET /api/auth/me} 返回：
 * <pre>{@code
 * {"id":1,"organization_id":1,"organization_name":"默认学校","username":"admin",
 *  "display_name":"系统管理员","role":"platform_admin","is_active":true,
 *  "must_change_password":false,"created_at":"..."}
 * }</pre>
 *
 * <p><b>字段名不用逐个写 {@code @JsonProperty}</b>：全局配了
 * {@code spring.jackson.property-naming-strategy: SNAKE_CASE}，
 * 所以 Java 的 camelCase 会自动变成 snake_case。
 * 只有这一个字段例外（见下）。
 *
 * <p>⚠ 注意 {@code passwordHash} **绝不出现在这里** —— 这是对外的视图，
 * 实体与视图分开，避免以后有人图省事直接把实体返回出去（那会泄露口令哈希）。
 */
public record UserResponse(
        Long id,
        Long organizationId,
        /** 来自 {@code organizations.name}，由查询时 join 或单独查一次补上。 */
        String organizationName,
        String username,
        String displayName,
        String role,
        @JsonProperty("is_active") Boolean isActive,
        Boolean mustChangePassword,
        LocalDateTime createdAt) {

    /**
     * 从实体构造。
     *
     * <p>关于 {@code @JsonProperty("is_active")}：全局 SNAKE_CASE 策略会把
     * record 组件 {@code isActive} 转成 {@code is_active} 吗？——**不可靠**。
     * Jackson 对 {@code is} 前缀的布尔属性有特殊处理（可能变成 {@code active}），
     * 这与「record 组件」的访问器命名叠加后行为不直观。
     * 所以这一个字段显式标注，不让它依赖推断。
     */
    public static UserResponse of(User user, String organizationName) {
        return new UserResponse(
                user.getId(),
                user.getOrganizationId(),
                organizationName,
                user.getUsername(),
                user.getDisplayName(),
                user.getRole(),
                user.getIsActive(),
                user.getMustChangePassword(),
                user.getCreatedAt());
    }
}
