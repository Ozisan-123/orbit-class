package com.orbit.scheduling.rule;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * {@code constraint_rules} 实体 —— 规则定义（P2 的核心表）。
 *
 * <p>「规则 = 数据 + 实现」里的那一半**数据**：用户能编辑的就是这张表的
 * {@code rule_type} 与 {@code params}，而 {@code rule_type} 指向的实现
 * 由开发者在 solver-core 里写一次。
 *
 * <p>⚠ {@code params} 是 MySQL {@code json} 列 → 需要 {@link JacksonTypeHandler}，
 * 且**必须配 {@code autoResultMap = true}**，否则写入正常但查询出来是 null。
 */
@TableName(value = "constraint_rules", autoResultMap = true)
public class ConstraintRuleEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long organizationId;
    private String ruleType;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> params;

    /** {@code hard} | {@code soft}（小写，与 §5.3 的列注释一致）。 */
    private String hardness;

    /** 仅 soft 有效。 */
    private Integer weight;

    /** {@code org} | {@code grade} | {@code class} | {@code teacher} | {@code subject}。 */
    private String scope;

    private Long scopeId;
    private Boolean enabled;

    /** 面向用户的人话描述。 */
    private String description;

    /** {@code manual} | {@code assistant}。 */
    private String source;

    /** **乐观锁**列 —— 对应接口里的 {@code expected_revision}。 */
    private Integer version;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

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

    public String getRuleType() {
        return ruleType;
    }

    public void setRuleType(String ruleType) {
        this.ruleType = ruleType;
    }

    public Map<String, Object> getParams() {
        return params;
    }

    public void setParams(Map<String, Object> params) {
        this.params = params;
    }

    public String getHardness() {
        return hardness;
    }

    public void setHardness(String hardness) {
        this.hardness = hardness;
    }

    public Integer getWeight() {
        return weight;
    }

    public void setWeight(Integer weight) {
        this.weight = weight;
    }

    public String getScope() {
        return scope;
    }

    public void setScope(String scope) {
        this.scope = scope;
    }

    public Long getScopeId() {
        return scopeId;
    }

    public void setScopeId(Long scopeId) {
        this.scopeId = scopeId;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
