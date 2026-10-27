package com.orbit.scheduling.rule;

import com.orbit.solver.core.rule.RuleParamSpec;

/**
 * 规则参数的 Schema —— 直接投影 {@link RuleParamSpec}。
 *
 * <p>⚠ 它是**投影**而不是新定义：字段与 solver-core 的 {@code RuleParamSpec} 一一对应，
 * 一行都不多做解释。原因见 {@link RuleTypeCatalog} 的类注释 ——
 * 这份 schema 是"前端表单 + 后端校验 + 助手工具定义"三处的**同一个来源**，
 * 一旦在这里"顺手加工"，三处就会各说各话。
 *
 * <p>{@code type} 用字符串（{@code INT}/{@code LONG}/{@code BOOL}/{@code STRING}/{@code ENUM}）
 * 而不是枚举，这样前端拿到就能直接 switch，也不用跟着后端枚举改名。
 */
public record RuleParamResponse(
        String name,
        String type,
        boolean required,
        Long min,
        Long max,
        java.util.List<String> enumValues,
        String label) {

    public static RuleParamResponse of(RuleParamSpec spec) {
        return new RuleParamResponse(
                spec.name(),
                spec.type().name(),
                spec.required(),
                spec.min(),
                spec.max(),
                spec.enumValues(),
                spec.label());
    }
}
