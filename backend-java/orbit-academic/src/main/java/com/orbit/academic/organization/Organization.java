package com.orbit.academic.organization;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * {@code organizations} 表实体（多租户的租户表）。
 *
 * <p>现在只用到 {@code id} 与 {@code name} —— 用户接口要返回
 * {@code organization_name}（"默认学校"），否则前端只能显示一个 org id。
 * 其余列（{@code created_at}）顺手映射，反正 V1 迁移里已经有它们。
 */
@TableName("organizations")
public class Organization {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
