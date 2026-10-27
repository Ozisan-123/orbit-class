package com.orbit.scheduling.snapshot;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;

import java.util.Map;

/**
 * **只读**的 {@code grades} 映射（academic 属主）。
 *
 * <p>{@code periods_per_day} 是 MySQL {@code json} 列 → 需要 {@link JacksonTypeHandler}
 * **且必须配 {@code autoResultMap = true}**，否则查询出来是 null。
 * 这个坑在 academic 的 Grade 上踩过一次，这里直接带上。
 *
 * <p>校验器要用它判断「这条课有没有落在该年级当天的课时范围内」。
 */
@TableName(value = "grades", autoResultMap = true)
public class GradeRef {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long organizationId;
    private String name;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Integer> periodsPerDay;

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

    public Map<String, Integer> getPeriodsPerDay() {
        return periodsPerDay;
    }

    public void setPeriodsPerDay(Map<String, Integer> periodsPerDay) {
        this.periodsPerDay = periodsPerDay;
    }
}
