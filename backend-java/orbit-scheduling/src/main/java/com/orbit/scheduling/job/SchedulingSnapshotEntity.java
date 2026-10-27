package com.orbit.scheduling.job;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * {@code scheduling_snapshots} 实体 —— **冻结的排课输入**（P3，铁律 3 的真正落地）。
 *
 * <p>P1/P2 时输入是"现查一遍"，因为求解就在同一个请求链路里。
 * 一旦求解挪到独立进程、可能排队几分钟后才开始跑，就必须冻结：
 * 否则排队期间教务数据被改，排出来的课表对应的是另一份数据，且事后无法复现。
 */
@TableName(value = "scheduling_snapshots", autoResultMap = true)
public class SchedulingSnapshotEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long organizationId;

    /** 对 payload 的规范化哈希 —— "这次求解用的到底是哪份数据"的凭据。 */
    private String fingerprint;

    /** 冻结的 {@code ScheduleInput}（序列化成 JSON）。 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> payload;

    private Integer gradeCount;
    private Integer classCount;
    private Integer subjectCount;
    private Integer teacherCount;
    private Integer assignmentCount;
    private Integer gradeSubjectCount;
    private Integer fixedSlotCount;
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

    public String getFingerprint() {
        return fingerprint;
    }

    public void setFingerprint(String fingerprint) {
        this.fingerprint = fingerprint;
    }

    public Map<String, Object> getPayload() {
        return payload;
    }

    public void setPayload(Map<String, Object> payload) {
        this.payload = payload;
    }

    public Integer getGradeCount() {
        return gradeCount;
    }

    public void setGradeCount(Integer gradeCount) {
        this.gradeCount = gradeCount;
    }

    public Integer getClassCount() {
        return classCount;
    }

    public void setClassCount(Integer classCount) {
        this.classCount = classCount;
    }

    public Integer getSubjectCount() {
        return subjectCount;
    }

    public void setSubjectCount(Integer subjectCount) {
        this.subjectCount = subjectCount;
    }

    public Integer getTeacherCount() {
        return teacherCount;
    }

    public void setTeacherCount(Integer teacherCount) {
        this.teacherCount = teacherCount;
    }

    public Integer getAssignmentCount() {
        return assignmentCount;
    }

    public void setAssignmentCount(Integer assignmentCount) {
        this.assignmentCount = assignmentCount;
    }

    public Integer getGradeSubjectCount() {
        return gradeSubjectCount;
    }

    public void setGradeSubjectCount(Integer gradeSubjectCount) {
        this.gradeSubjectCount = gradeSubjectCount;
    }

    public Integer getFixedSlotCount() {
        return fixedSlotCount;
    }

    public void setFixedSlotCount(Integer fixedSlotCount) {
        this.fixedSlotCount = fixedSlotCount;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
