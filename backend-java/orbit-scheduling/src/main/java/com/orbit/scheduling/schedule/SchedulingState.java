package com.orbit.scheduling.schedule;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * {@code scheduling_state} 实体 —— **每个组织一行**的排课状态机。
 *
 * <p>唯一约束 {@code uq_scheduling_state_org} 保证了一个学校同时只有一个排课任务，
 * 这是整套并发控制的地基：抢锁、CAS、租约都围绕这一行展开。
 *
 * <p>{@code job_token} 是 **fencing token**：每次发起排课生成一个新的随机串，
 * 后续所有状态更新都必须带上它（{@code WHERE ... AND job_token = ?}）。
 * 作用：一个被租约超时判定为"死了"的旧任务，即使之后活过来继续写，
 * 也会因为 token 不匹配而影响不到新任务的状态。
 */
@TableName("scheduling_state")
public class SchedulingState {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long organizationId;
    /** {@code idle} / {@code running} / {@code done} / {@code failed}。 */
    private String status;
    private String message;
    private Long ownerUserId;
    private String ownerName;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private String jobToken;

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

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public Long getOwnerUserId() {
        return ownerUserId;
    }

    public void setOwnerUserId(Long ownerUserId) {
        this.ownerUserId = ownerUserId;
    }

    public String getOwnerName() {
        return ownerName;
    }

    public void setOwnerName(String ownerName) {
        this.ownerName = ownerName;
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

    public String getJobToken() {
        return jobToken;
    }

    public void setJobToken(String jobToken) {
        this.jobToken = jobToken;
    }
}
