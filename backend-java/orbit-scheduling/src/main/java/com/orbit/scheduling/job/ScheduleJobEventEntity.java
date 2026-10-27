package com.orbit.scheduling.job;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * {@code schedule_job_events} 实体 —— 任务事件流。
 *
 * <p>事件名（写成常量避免拼错，因为它是查询条件）：
 * {@code enqueued} / {@code claimed} / {@code heartbeat} / {@code completed}
 * / {@code failed} / {@code takeover} / {@code cancelled} / {@code retried}。
 */
@TableName("schedule_job_events")
public class ScheduleJobEventEntity {

    public static final String ENQUEUED = "enqueued";
    public static final String CLAIMED = "claimed";
    public static final String HEARTBEAT = "heartbeat";
    public static final String COMPLETED = "completed";
    public static final String FAILED = "failed";
    /** 接管：领到的是一条租约已过期的 running —— 说明上一个 worker 没能收尾。 */
    public static final String TAKEOVER = "takeover";
    public static final String CANCELLED = "cancelled";
    public static final String RETRIED = "retried";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long organizationId;
    private Long jobId;
    private String event;
    private String workerId;
    private Integer attempt;
    private String detail;
    private LocalDateTime createdAt;

    public static ScheduleJobEventEntity of(long organizationId, long jobId, String event,
                                            String workerId, Integer attempt, String detail) {
        ScheduleJobEventEntity entity = new ScheduleJobEventEntity();
        entity.setOrganizationId(organizationId);
        entity.setJobId(jobId);
        entity.setEvent(event);
        entity.setWorkerId(workerId);
        entity.setAttempt(attempt);
        entity.setDetail(detail);
        entity.setCreatedAt(com.orbit.common.util.UtcTimes.now());
        return entity;
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

    public Long getJobId() {
        return jobId;
    }

    public void setJobId(Long jobId) {
        this.jobId = jobId;
    }

    public String getEvent() {
        return event;
    }

    public void setEvent(String event) {
        this.event = event;
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

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
