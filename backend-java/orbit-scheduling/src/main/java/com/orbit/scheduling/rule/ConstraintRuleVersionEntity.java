package com.orbit.scheduling.rule;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * {@code constraint_rule_versions} 实体 —— 规则集版本。
 *
 * <p>每次规则表发生变更就重算 {@code rule_set_hash}：
 * 哈希已存在则不新建版本（改回去等于没改），否则 {@code version_no = max+1}。
 *
 * <p>⚠ {@code rules} 存的是**当时全部生效规则的冻结快照**，不是只存 hash ——
 * 因为审计链要求「这版课表用的哪套规则」任何时候都能点开，
 * 只存 hash 的话规则被删改后历史就还原不出来了。
 */
@TableName(value = "constraint_rule_versions", autoResultMap = true)
public class ConstraintRuleVersionEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long organizationId;
    private Integer versionNo;
    private String ruleSetHash;

    /** 冻结的规则快照（只含 enabled 的）。 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<Map<String, Object>> rules;

    private Integer ruleCount;
    private String note;
    private LocalDateTime createdAt;

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

    public Integer getVersionNo() {
        return versionNo;
    }

    public void setVersionNo(Integer versionNo) {
        this.versionNo = versionNo;
    }

    public String getRuleSetHash() {
        return ruleSetHash;
    }

    public void setRuleSetHash(String ruleSetHash) {
        this.ruleSetHash = ruleSetHash;
    }

    public List<Map<String, Object>> getRules() {
        return rules;
    }

    public void setRules(List<Map<String, Object>> rules) {
        this.rules = rules;
    }

    public Integer getRuleCount() {
        return ruleCount;
    }

    public void setRuleCount(Integer ruleCount) {
        this.ruleCount = ruleCount;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
