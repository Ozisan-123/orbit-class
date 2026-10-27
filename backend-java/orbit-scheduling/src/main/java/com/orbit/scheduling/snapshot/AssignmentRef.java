package com.orbit.scheduling.snapshot;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * **只读**的 {@code teacher_assignments} 映射（academic 属主）—— 「某教师教某班的某科目」。
 *
 * <p>排课的**第二个核心输入**：它决定了一节课能由谁上。
 * 引擎会把它当作"候选教师集合"，而不变量 {@code TeacherNoConflict} 保证同一个教师
 * 同一时段不会出现在两个班。
 */
@TableName("teacher_assignments")
public class AssignmentRef {

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
