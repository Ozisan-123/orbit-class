package com.orbit.scheduling.schedule;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * {@code schedules} 实体 —— **当前已发布课表**的投影。
 *
 * <p>它和 {@link com.orbit.scheduling.version.ScheduleVersionEntry} 的区别：
 * <ul>
 *   <li>{@code schedule_version_entries} 是**每版**课表的明细（历史，不可变）</li>
 *   <li>{@code schedules} 只是"最新发布的那一版"的拷贝，供前端默认查看用</li>
 * </ul>
 * 发布时会先清空本表再整体重写（见 {@code VersionService.publish}）。
 *
 * <p>表名 {@code schedules} 与类名 {@code ScheduleEntity} 不一致是刻意的：
 * {@code Schedule} 这个词在本模块里已经用来表示"排课"这件事（包名、服务名），
 * 用 {@code ScheduleEntity} 避免读代码时混淆"一条课表记录"与"排课这个动作"。
 */
@TableName("schedules")
public class ScheduleEntity {

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
