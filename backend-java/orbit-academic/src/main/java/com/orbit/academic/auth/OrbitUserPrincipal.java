package com.orbit.academic.auth;

import com.orbit.academic.user.User;
import com.orbit.academic.user.UserResponse;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * 认证主体 —— Spring Security 的 {@link UserDetails}。
 *
 * <p>它比 Python 的「往函数里传个 User 实体」多带了三样业务上必需的东西：
 * <ul>
 *   <li>{@code organizationId} —— <b>租户隔离的依据</b>。每个查询都要用它过滤，
 *       否则一个学校能看到另一个学校的数据（Python 侧是靠 {@code user.organization_id} 手工过滤的）</li>
 *   <li>{@code displayName} —— 排课状态要显示「张三正在排课」</li>
 *   <li>{@code mustChangePassword} —— 前端据此强制弹出改密框</li>
 * </ul>
 *
 * <p><b>为什么不直接用 Spring 自带的 {@code org.springframework.security.core.userdetails.User}</b>：
 * 它只有 username/password/authorities，装不下上面三个字段，
 * 于是每个 Controller 都要再查一次库才知道当前用户属于哪个组织 —— 那等于把认证又做了一遍。
 *
 * <p>⚠ 口令哈希留在这里是**故意的**：{@code DaoAuthenticationProvider} 需要它来比对。
 * 它只在单次请求的安全上下文里存活，不会进入任何响应体
 * （对外用的是 {@link UserResponse}，那个里面没有口令字段）。
 */
public class OrbitUserPrincipal implements UserDetails {

    private final Long userId;
    private final Long organizationId;
    private final String username;
    private final String displayName;
    private final String passwordHash;
    private final String role;
    private final boolean active;
    private final boolean mustChangePassword;
    private final LocalDateTime createdAt;

    public OrbitUserPrincipal(Long userId, Long organizationId, String username, String displayName,
                              String passwordHash, String role, boolean active,
                              boolean mustChangePassword, LocalDateTime createdAt) {
        this.userId = userId;
        this.organizationId = organizationId;
        this.username = username;
        this.displayName = displayName;
        this.passwordHash = passwordHash;
        this.role = role;
        this.active = active;
        this.mustChangePassword = mustChangePassword;
        this.createdAt = createdAt;
    }

    public static OrbitUserPrincipal from(User user) {
        return new OrbitUserPrincipal(
                user.getId(),
                user.getOrganizationId(),
                user.getUsername(),
                user.getDisplayName(),
                user.getPasswordHash(),
                user.getRole(),
                user.active(),
                Boolean.TRUE.equals(user.getMustChangePassword()),
                user.getCreatedAt());
    }

    // ------------------------------------------------------------------ 业务字段

    public Long userId() {
        return userId;
    }

    public Long organizationId() {
        return organizationId;
    }

    public String displayName() {
        return displayName;
    }

    public String role() {
        return role;
    }

    public boolean mustChangePassword() {
        return mustChangePassword;
    }

    /** 对应 Python 的 {@code require_admin}：{@code admin} 与 {@code platform_admin} 都算管理员。 */
    public boolean isAdmin() {
        return "admin".equals(role) || "platform_admin".equals(role);
    }

    /** 对应 Python 的 {@code require_platform_admin}。 */
    public boolean isPlatformAdmin() {
        return "platform_admin".equals(role);
    }

    /** 转成对外视图（口令字段不进这里）。 */
    public UserResponse toResponse(String organizationName) {
        return new UserResponse(userId, organizationId, organizationName, username,
                displayName, role, active, mustChangePassword, createdAt);
    }

    // ------------------------------------------------------------------ UserDetails

    /**
     * 权限用 {@code ROLE_<大写的 role>}。
     *
     * <p>这样 {@code hasRole("ADMIN")} 这类表达式能直接用；
     * 但**本项目的权限判断主要走 {@code isAdmin()} 方法**（见上面），
     * 因为 Python 的角色是三个字符串字面量，用方法表达更直白、也更好搜。
     */
    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + String.valueOf(role).toUpperCase()));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    /**
     * 停用账号不能登录。
     *
     * <p>返回 false 时 {@code DaoAuthenticationProvider} 抛 {@code DisabledException}；
     * 登录接口会把它和其他认证失败**统一**变成
     * {@code 401 {"detail":"用户名或密码错误"}} —— 与 Python 一致
     * （Python 那段也是 {@code if not user or not user.is_active or not verify_password:} 同一个分支），
     * 这样就不会泄露「这个用户名存在但被停用了」。
     */
    @Override
    public boolean isEnabled() {
        return active;
    }
}
