package com.orbit.scheduling.rule;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * 编辑规则 —— {@code PUT /api/rules/{id}}。
 *
 * <p>⚠ {@code expectedRevision} **必填**（方案 §7.5③ 的要求）。
 * 规则是多人协作编辑的对象：列表上看的是旧值、提交时数据库可能已经变了，
 * 没有这个字段就是"后提交者静默覆盖前者"。
 *
 * <p>{@code ruleType} 不在编辑范围内 —— 换类型等于换一条规则（参数含义完全不同），
 * 应该删掉重建。这样也避免了"改类型后旧参数残留在新类型上"的诡异状态。
 */
public record RuleUpdateRequest(
        Map<String, Object> params,

        String hardness,

        Integer weight,

        @Size(max = 255, message = "Field required")
        String description,

        @NotNull(message = "Field required")
        Integer expectedRevision) {
}
