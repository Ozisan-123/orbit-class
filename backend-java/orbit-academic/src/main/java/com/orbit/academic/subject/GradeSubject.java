package com.orbit.academic.subject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * {@code grade_subjects} 表实体 —— 「某年级某科目每周几课时」。
 *
 * <p>唯一约束：{@code uq_grade_subject_org (organization_id, grade_id, subject_id)}。
 *
 * <p>这个表是**排课输入的核心**之一：引擎按它给每个班生成「科目 × 周课时」的需求。
 */
@TableName("grade_subjects")
public class GradeSubject {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long organizationId;
    private Long gradeId;
    private Long subjectId;
    private Integer weeklyHours;

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

    public Integer getWeeklyHours() {
        return weeklyHours;
    }

    public void setWeeklyHours(Integer weeklyHours) {
        this.weeklyHours = weeklyHours;
    }
}
