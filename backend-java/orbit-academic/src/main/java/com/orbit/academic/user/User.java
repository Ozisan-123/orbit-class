package com.orbit.academic.user;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * {@code users} 表实体 —— 与现有库逐列对应（见 V1__baseline_legacy.sql）。
 *
 * <p>字段用 camelCase，靠 MyBatis-Plus 的 {@code map-underscore-to-camel-case}
 * 映射到 snake_case 列名（{@code organizationId → organization_id}）。
 *
 * <p>⚠ {@code isActive} / {@code mustChangePassword} 这两个字段名**不能改成**
 * {@code active} / {@code changePassword} —— 列名是从**字段名**推导的，
 * 改了就映射不上 {@code is_active} / {@code must_change_password}，
 * 而且 MyBatis-Plus 不会报错，只会查不到值（静默失效）。
 *
 * <p>用普通类而不是 record：MyBatis-Plus 的实体需要可变的 setter 来填充结果。
 * 对外输出的 DTO 用 {@link UserResponse}（record）。
 */
@TableName("users")
public class User {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long organizationId;
    private String username;
    private String displayName;
    /** Python 的 {@code pbkdf2_sha256$310000$盐$摘要}，由 PythonPbkdf2PasswordEncoder 校验。 */
    private String passwordHash;
    /** {@code platform_admin} / {@code admin} / {@code operator}。 */
    private String role;
    private Boolean isActive;
    private Boolean mustChangePassword;
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getOrganizationId() {
        return organizationId;
    }

    public void setOrganizationId(Long organizationId) {
        this.organizationId = organizationId;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public Boolean getIsActive() {
        return isActive;
    }

    public void setIsActive(Boolean isActive) {
        this.isActive = isActive;
    }

    public Boolean getMustChangePassword() {
        return mustChangePassword;
    }

    public void setMustChangePassword(Boolean mustChangePassword) {
        this.mustChangePassword = mustChangePassword;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    /** {@code is_active} 列为 {@code tinyint(1)}，可能为 null；null 一律当停用处理。 */
    public boolean active() {
        return Boolean.TRUE.equals(isActive);
    }
}
