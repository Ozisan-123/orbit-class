package com.orbit.scheduling.capacity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * {@code schedule_worker_slots} 实体 —— **容量槽位**（每行 = 一个可用的求解名额）。
 *
 * <p>表里预置固定几行（{@code MAX_CONCURRENT_SCHEDULES}，默认 2），
 * 抢到某个槽位才能开始排课，避免 8 个人同时点"排课"把 CPU 打满。
 *
 * <p>实现方式很关键：**不是**"先查有没有空的、再更新"，而是
 * <pre>{@code
 * UPDATE schedule_worker_slots
 *    SET organization_id=?, job_token=?, lease_until=?
 *  WHERE id=? AND (job_token IS NULL OR lease_until IS NULL OR lease_until < now)
 * }</pre>
 * 然后看**受影响行数**是否等于 1。这正好是 §4.2 选 MyBatis 而不是 JPA 的原因：
 * 条件 UPDATE 的 rowcount 就是"我抢到了吗"的答案，一条语句原子完成。
 *
 * <p>{@code lease_until} 让槽位在持有者崩溃后能自动回收（不需要人工清理）。
 */
@TableName("schedule_worker_slots")
public class ScheduleWorkerSlot {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long organizationId;
    private String jobToken;
    private LocalDateTime leaseUntil;

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

    public LocalDateTime getLeaseUntil() {
        return leaseUntil;
    }

    public void setLeaseUntil(LocalDateTime leaseUntil) {
        this.leaseUntil = leaseUntil;
    }
}
