package com.orbit.scheduling.snapshot;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * **只读**的 {@code grade_fixed_slots} 映射（academic 属主）——
 * 「某年级在某天某节固定上某科目」，引擎会把它展开到该年级的每个班。
 *
 * <p>注意它是**年级级**的（`grade_fixed_slots`），不是班级级（`fixed_slots`）。
 * 后者当前没有参与建模（Python 的引擎只读了年级级的那张），
 * 这里保持一致 —— 不"顺手"多加输入，否则模型会与 Python 不同。
 */
@TableName("grade_fixed_slots")
public class GradeFixedSlotRef {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long organizationId;
    private Long gradeId;
    private Long subjectId;
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

    public Long getGradeId() {
        return gradeId;
    }

    public void setGradeId(Long gradeId) {
        this.gradeId = gradeId;
    }

    public Long getSubjectId() {
        return subjectId;
    }

    public void setSubjectId(Long subjectId) {
        this.subjectId = subjectId;
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
