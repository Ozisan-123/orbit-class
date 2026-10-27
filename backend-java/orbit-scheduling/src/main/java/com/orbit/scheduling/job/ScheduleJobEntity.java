package com.orbit.scheduling.job;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * {@code schedule_jobs} 实体 —— 持久任务队列的一行（P3）。
 *
 * <p>它替代了 P1/P2 的"进程内线程"：进程重启后任务**不会消失**，
 * 而且能回答"排队中吗 / 排第几 / 上次为什么失败"。
 *
 * <p>状态机：{@code pending → running → done | failed | cancelled}，
 * 租约过期后 {@code running} 可以被重新领走（{@code attempt + 1}）。
 */
@TableName("schedule_jobs")
public class ScheduleJobEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long organizationId;

    /** **栅栏**：worker 每次回写都要带它；任务被接管后旧 token 失效。 */
    private String jobToken;

    private String status;
    private String ruleSetHash;
    private Long snapshotId;

    /** **租约**：worker 每 15 秒续一次；没续上就允许别人接管。 */
    private LocalDateTime leaseUntil;
    private LocalDateTime heartbeatAt;
    private String workerId;

    private Integer attempt;
    private Integer maxAttempts;
    private Integer progress;
    private Boolean cancelRequested;
    private String lastError;

    /** 求解结果摘要（诊断与列表页用，不必再去读版本表）。 */
    private String solverStatus;
    private String modelFingerprint;
    private Long objectiveValue;
    private Long versionId;
    private Integer entryCount;

    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;

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

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getRuleSetHash() {
        return ruleSetHash;
    }

    public void setRuleSetHash(String ruleSetHash) {
        this.ruleSetHash = ruleSetHash;
    }

    public Long getSnapshotId() {
        return snapshotId;
    }

    public void setSnapshotId(Long snapshotId) {
        this.snapshotId = snapshotId;
    }

    public LocalDateTime getLeaseUntil() {
        return leaseUntil;
    }

    public void setLeaseUntil(LocalDateTime leaseUntil) {
        this.leaseUntil = leaseUntil;
    }

    public LocalDateTime getHeartbeatAt() {
        return heartbeatAt;
    }

    public void setHeartbeatAt(LocalDateTime heartbeatAt) {
        this.heartbeatAt = heartbeatAt;
    }

    public String getWorkerId() {
        return workerId;
    }

    public void setWorkerId(String workerId) {
        this.workerId = workerId;
    }

    public Integer getAttempt() {
        return attempt;
    }

    public void setAttempt(Integer attempt) {
        this.attempt = attempt;
    }

    public Integer getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(Integer maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public Integer getProgress() {
        return progress;
    }

    public void setProgress(Integer progress) {
        this.progress = progress;
    }

    public Boolean getCancelRequested() {
        return cancelRequested;
    }

    public void setCancelRequested(Boolean cancelRequested) {
        this.cancelRequested = cancelRequested;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
    }

    public String getSolverStatus() {
        return solverStatus;
    }

    public void setSolverStatus(String solverStatus) {
        this.solverStatus = solverStatus;
    }

    public String getModelFingerprint() {
        return modelFingerprint;
    }

    public void setModelFingerprint(String modelFingerprint) {
        this.modelFingerprint = modelFingerprint;
    }

    public Long getObjectiveValue() {
        return objectiveValue;
    }

    public void setObjectiveValue(Long objectiveValue) {
        this.objectiveValue = objectiveValue;
    }

    public Long getVersionId() {
        return versionId;
    }

    public void setVersionId(Long versionId) {
        this.versionId = versionId;
    }

    public Integer getEntryCount() {
        return entryCount;
    }

    public void setEntryCount(Integer entryCount) {
        this.entryCount = entryCount;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(LocalDateTime startedAt) {
        this.startedAt = startedAt;
    }

    public LocalDateTime getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(LocalDateTime finishedAt) {
        this.finishedAt = finishedAt;
    }
}
