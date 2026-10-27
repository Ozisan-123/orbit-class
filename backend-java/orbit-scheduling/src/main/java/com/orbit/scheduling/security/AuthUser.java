package com.orbit.scheduling.security;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * **只读**的 {@code users} 表映射 —— scheduling 用它做鉴权，不做用户管理。
 *
 * <p>⚠ 这张表属于 **academic 属主**。这里读它是否违反 §3 铁律 2？
 * 不违反 —— 铁律 2 说的是「禁止跨服务**写**库」。鉴权必须知道"这个 token 对应谁、
 * 属于哪个组织、是什么角色"，读是唯一可行的方式（Python 单进程时也是这么做的）。
 *
 * <p>⚠ 但这确实是**一处耦合**，拆成独立库时会断：
 * 届时要么把鉴权放到网关（校验 token 后把 userId/organizationId/role 放进请求头），
 * 要么各服务维护一份只读的用户投影。这一条已记入方案 §15 的 P1 遗留。
 *
 * <p>只映射鉴权需要的列 —— 刻意**不映射 {@code password_hash}**：
 * scheduling 从不校验口令（那是 academic 的登录接口的事），
 * 不映射就没有泄露风险，也避免别人误用。
 */
@TableName("users")
public class AuthUser {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long organizationId;
    private String username;
    private String displayName;
    private String role;
    private Boolean isActive;

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

    public boolean active() {
        return Boolean.TRUE.equals(isActive);
    }
}
