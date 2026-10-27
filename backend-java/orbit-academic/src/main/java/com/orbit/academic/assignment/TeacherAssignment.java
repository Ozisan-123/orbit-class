package com.orbit.academic.assignment;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * {@code teacher_assignments} 表实体 —— 「某教师教某班的某科目」。
 *
 * <p>唯一约束 {@code uq_assignment_org (organization_id, teacher_id, class_id, subject_id)}。
 * 这张表是排课的**硬约束来源之一**：引擎靠它知道「一个班的一个科目由哪位老师上」。
 */
@TableName("teacher_assignments")
public class TeacherAssignment {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long organizationId;
    private Long teacherId;
    private Long classId;
    private Long subjectId;

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

    public Long getTeacherId() {
        return teacherId;
    }

    public void setTeacherId(Long teacherId) {
        this.teacherId = teacherId;
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
}
