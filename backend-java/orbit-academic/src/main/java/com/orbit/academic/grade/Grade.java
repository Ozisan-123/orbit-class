package com.orbit.academic.grade;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;

import java.util.Map;

/**
 * {@code grades} 表实体。
 *
 * <h2>⚠ {@code autoResultMap = true} 不能省</h2>
 * {@code periods_per_day} 是 MySQL 的 {@code json} 列，需要用
 * {@link JacksonTypeHandler} 在「JSON 文本 ↔ Map」之间转换。
 * 而 **MyBatis-Plus 只在使用 {@code autoResultMap} 时才会把这个 typeHandler
 * 用在查询结果映射上** —— 不写它：
 * <ul>
 *   <li>插入是好的（typeHandler 在写入路径生效）</li>
 *   <li>**查询出来是 null**（或直接报类型转换错误），而**不报缺少配置**</li>
 * </ul>
 * 又是一个「配置漏了但只坏一半路径」的坑，所以这里显式写上并注释。
 *
 * <p>字段类型用 {@code Map<String, Integer>}（"Mon" → 6）而不是 {@code Object}：
 * 它就是「每天几节课」，写清楚类型才能在编译期挡住错误形状的数据。
 */
@TableName(value = "grades", autoResultMap = true)
public class Grade {

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
