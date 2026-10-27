package com.orbit.academic.fixedslot;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * {@code fixed_slots} 表实体 —— 「某班在某天某节固定上某科目（由某老师上）」。
 *
 * <p>唯一的约束是 {@code uq_fixed_slot_org (organization_id, class_id, day_of_week, period_index)}
 * —— 一个班的一个时段只能有一节固定课。
 *
 * <p>这是排课的三类"固定"输入之一（另外两类是 {@code grade_fixed_slots} 与教师不可用时段），
 * 对应 solver-core 里 {@code RuleContext} 收敛掉的那些 {@code fixed} 处理。
 */
@TableName("fixed_slots")
public class FixedSlot {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long organizationId;
    private Long classId;
    private Long subjectId;
    private Long teacherId;
    private Integer dayOfWeek;
    private Integer periodIndex;

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

    public Long getClassId() {
        return classId;
    }

    public void setClassId(Long classId) {
        this.classId = classId;
    }

    public Long getSubjectId() {
        return subjectId;
    }

    public void setSubjectId(Long subjectId) {
        this.subjectId = subjectId;
    }

    public Long getTeacherId() {
        return teacherId;
    }

    public void setTeacherId(Long teacherId) {
        this.teacherId = teacherId;
    }

    public Integer getDayOfWeek() {
        return dayOfWeek;
    }

    public void setDayOfWeek(Integer dayOfWeek) {
        this.dayOfWeek = dayOfWeek;
    }

    public Integer getPeriodIndex() {
        return periodIndex;
    }

    public void setPeriodIndex(Integer periodIndex) {
        this.periodIndex = periodIndex;
    }
}
