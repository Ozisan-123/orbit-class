package com.orbit.scheduling.diagnosis;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * {@code schedule_diagnoses} 实体。
 *
 * <p>⚠ **必须落库**（方案 §5.2 的原话）：最小冲突集要"软化硬约束重求解"，
 * 很贵；每次前端刷新都重算不可接受。
 *
 * <p>{@code jobToken} 可为 null —— 按需诊断（还没发起排课时先问"为什么排不出来"）
 * 没有任务身份。P3 引入 {@code schedule_jobs} 后会再加 {@code job_id}。
 */
@TableName(value = "schedule_diagnoses", autoResultMap = true)
public class ScheduleDiagnosisEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long organizationId;
    private String jobToken;
    private Long versionId;

    /** {@code OK} | {@code INFEASIBLE} | {@code UNKNOWN}。 */
    private String kind;

    /** 最高严重度：{@code error} | {@code warning} | {@code info}。 */
    private String severity;

    /** {@code {"findings": [...], "rule_set_hash": "...", ...}}。 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> payload;

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

    public String getJobToken() {
        return jobToken;
    }

    public void setJobToken(String jobToken) {
        this.jobToken = jobToken;
    }

    public Long getVersionId() {
        return versionId;
    }

    public void setVersionId(Long versionId) {
        this.versionId = versionId;
    }

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public String getSeverity() {
        return severity;
    }

    public void setSeverity(String severity) {
        this.severity = severity;
    }

    public Map<String, Object> getPayload() {
        return payload;
    }

    public void setPayload(Map<String, Object> payload) {
        this.payload = payload;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
