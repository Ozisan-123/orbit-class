package com.orbit.scheduling.version;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * {@code schedule_version_entries} 实体 —— 一版课表里的一节课。
 *
 * <p>唯一约束有两层意义（都在这张表的 DDL 里）：
 * <ul>
 *   <li>{@code uq_version_class_slot (version_id, class_id, day_of_week, period_index)}：
 *       一个班的同一时段只能有一节课</li>
 *   <li>{@code ix_version_teacher_slot (version_id, teacher_id, day_of_week, period_index)}
 *       只是普通索引 —— **数据库不禁止教师撞课**，靠 {@code VersionValidator} 在
 *       换课/发布时校验</li>
 * </ul>
 *
 * <p>⚠ 换课时的"腾挪"手法（见 {@code SwapService}）依赖这个唯一约束：
 * 先把要移动的记录改成负值（{@code day_of_week = -1, period_index = -id}）暂时"移出"唯一键的冲突范围，
 * 再放到目标位置。所以 {@code day_of_week} 允许出现负数 —— **只在同一事务的中间态**。
 */
@TableName("schedule_version_entries")
public class ScheduleVersionEntry {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long organizationId;
    private Long versionId;
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

    public Long getVersionId() {
        return versionId;
    }

    public void setVersionId(Long versionId) {
        this.versionId = versionId;
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
