package com.orbit.academic.teacher;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * {@code teachers} 表实体。
 *
 * <p>{@code max_weekly_hours} 是排课的硬约束输入：一位教师一周最多上多少节。
 * 注意它和「教师不可用时段」是两回事 —— 后者在 solver-core 里是规则参数，不在这张表。
 */
@TableName("teachers")
public class Teacher {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long organizationId;
    private String name;
    private Long subjectId;
    private Integer maxWeeklyHours;

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

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Long getSubjectId() {
        return subjectId;
    }

    public void setSubjectId(Long subjectId) {
        this.subjectId = subjectId;
    }

    public Integer getMaxWeeklyHours() {
        return maxWeeklyHours;
    }

    public void setMaxWeeklyHours(Integer maxWeeklyHours) {
        this.maxWeeklyHours = maxWeeklyHours;
    }
}
