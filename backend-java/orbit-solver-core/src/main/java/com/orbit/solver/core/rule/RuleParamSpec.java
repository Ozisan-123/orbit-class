package com.orbit.solver.core.rule;

import java.util.List;

/**
 * 规则的单个参数声明。
 *
 * <p>一份 schema 同时驱动三处用途，这是规则系统的关键设计：
 * <ol>
 *   <li>后端保存规则时做参数校验</li>
 *   <li>前端按它动态渲染表单（不需要为每个规则手写页面）</li>
 *   <li>助手的工具定义（新增规则类型时助手自动会用，无需改 prompt）</li>
 * </ol>
 */
public record RuleParamSpec(
        String name,
        ParamType type,
        boolean required,
        Long min,
        Long max,
        List<String> enumValues,
        String label) {

    public enum ParamType {
        INT, LONG, BOOL, STRING, ENUM
    }

    public static RuleParamSpec intParam(String name, String label, long min, long max) {
        return new RuleParamSpec(name, ParamType.INT, true, min, max, List.of(), label);
    }

    public static RuleParamSpec optionalInt(String name, String label, long min, long max) {
        return new RuleParamSpec(name, ParamType.INT, false, min, max, List.of(), label);
    }

    public static RuleParamSpec enumParam(String name, String label, List<String> values) {
        return new RuleParamSpec(name, ParamType.ENUM, true, null, null, List.copyOf(values), label);
    }

    public static RuleParamSpec boolParam(String name, String label) {
        return new RuleParamSpec(name, ParamType.BOOL, false, null, null, List.of(), label);
    }
}
