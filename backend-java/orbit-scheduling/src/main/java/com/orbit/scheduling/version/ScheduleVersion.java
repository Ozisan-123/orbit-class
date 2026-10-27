package com.orbit.scheduling.version;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * {@code schedule_versions} 实体 —— 一版课表。
 *
 * <p>生命周期：{@code draft} → {@code published} → {@code archived}。
 * 只有草稿可以改（改名/调课），发布时会把上一版已发布的置为 {@code archived}。
 *
 * <p>{@code revision} 是**乐观锁**：客户端改之前先拿到当前 revision，
 * 提交时带上 {@code expected_revision}，不匹配就 409
 * （{@code 版本已被其他用户修改，请刷新后重试}）。
 * 与 {@code job_token}（悲观栅栏）是两种不同的并发控制，别混。
 */
@TableName("schedule_versions")
public class ScheduleVersion {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long organizationId;
    private Integer versionNo;
    private String name;
    private String description;
    private String status;
    private Integer revision;
    private Long createdBy;
    private Long publishedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime publishedAt;

    /**
     * 生成这一版时用的规则集哈希（P2 新增，V2 迁移加的列）。
     *
     * <p>方案 §5.2：「评分必须与 rule_set_hash 绑定 —— 同一张课表在不同规则集下分数不同，
     * **不记 hash 的分数没有意义**」。有了它，审计链才能回答
     * 「这版课表用的哪套规则」，并保证**P1 之前生成的版本为 null**（那时还没有规则表）。
     */
    private String ruleSetHash;

    /** 用的哪份**输入快照**（P3）。与 ruleSetHash、modelFingerprint 合起来可精确复现。 */
    private String snapshotFingerprint;

    /** 编译出的 CP-SAT 模型指纹（P3）。 */
    private String modelFingerprint;

    public String getRuleSetHash() {
        return ruleSetHash;
    }

    public void setRuleSetHash(String ruleSetHash) {
        this.ruleSetHash = ruleSetHash;
    }

    public String getSnapshotFingerprint() {
        return snapshotFingerprint;
    }

    public void setSnapshotFingerprint(String snapshotFingerprint) {
        this.snapshotFingerprint = snapshotFingerprint;
    }

    public String getModelFingerprint() {
        return modelFingerprint;
    }

    public void setModelFingerprint(String modelFingerprint) {
        this.modelFingerprint = modelFingerprint;
    }

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

    public Integer getVersionNo() {
        return versionNo;
    }

    public void setVersionNo(Integer versionNo) {
        this.versionNo = versionNo;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getRevision() {
        return revision;
    }

    public void setRevision(Integer revision) {
        this.revision = revision;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
    }

    public Long getPublishedBy() {
        return publishedBy;
    }

    public void setPublishedBy(Long publishedBy) {
        this.publishedBy = publishedBy;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public LocalDateTime getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(LocalDateTime publishedAt) {
        this.publishedAt = publishedAt;
    }
}
