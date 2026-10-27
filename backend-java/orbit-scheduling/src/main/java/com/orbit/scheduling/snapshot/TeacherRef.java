package com.orbit.scheduling.snapshot;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** **只读**的 {@code teachers} 映射（academic 属主）—— 展示姓名 + 求解需要的周课时上限。 */
@TableName("teachers")
public class TeacherRef {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long organizationId;
    private String name;
    private Long subjectId;
    /** 求解的硬约束输入：一位教师一周最多上多少节。 */
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
