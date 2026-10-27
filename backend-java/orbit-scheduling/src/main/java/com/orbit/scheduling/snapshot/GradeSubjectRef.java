package com.orbit.scheduling.snapshot;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * **只读**的 {@code grade_subjects} 映射（academic 属主）—— 「某年级某科目每周几课时」。
 *
 * <p>这是排课最核心的**需求输入**：引擎要靠它知道每个班的每门课要排几节。
 */
@TableName("grade_subjects")
public class GradeSubjectRef {

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
