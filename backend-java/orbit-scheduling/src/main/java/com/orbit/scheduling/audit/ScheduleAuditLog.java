package com.orbit.scheduling.audit;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * {@code schedule_audit_logs} 实体 —— 课表操作审计。
 *
 * <p>action 取值（Python 侧的原字符串，逐字保留）：
 * {@code generated} / {@code updated} / {@code copied} / {@code published}
 * / {@code swapped} / {@code cross_swapped} / {@code moved}。
 *
 * <p>⚠ {@code details} 是 MySQL {@code json} 列 → 需要 {@link JacksonTypeHandler}，
 * 而且**必须配 {@code autoResultMap = true}**：否则写入正常、**查询出来是 null**
 * （typeHandler 只在结果映射启用时生效）。这个坑在 grades 的 {@code periods_per_day} 上踩过一次。
 */
@TableName(value = "schedule_audit_logs", autoResultMap = true)
public class ScheduleAuditLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long organizationId;
    private Long versionId;
    private Long userId;
    private String userName;
    private String action;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> details;

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

    public Long getVersionId() {
        return versionId;
    }

    public void setVersionId(Long versionId) {
        this.versionId = versionId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getUserName() {
        return userName;
    }

    public void setUserName(String userName) {
        this.userName = userName;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public Map<String, Object> getDetails() {
        return details;
    }

    public void setDetails(Map<String, Object> details) {
        this.details = details;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
