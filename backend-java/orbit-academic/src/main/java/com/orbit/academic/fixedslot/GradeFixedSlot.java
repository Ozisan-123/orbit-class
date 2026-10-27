package com.orbit.academic.fixedslot;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * {@code grade_fixed_slots} 表实体 —— 「某年级在某天某节固定上某科目」。
 *
 * <p>与 {@link FixedSlot} 的区别：{@code fixed_slots} 是**班级**级（指定到教师），
 * 这张是**年级**级（该年级所有班同时上同一科目，教师由分配关系推出来）。
 */
@TableName("grade_fixed_slots")
public class GradeFixedSlot {

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
