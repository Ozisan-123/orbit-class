package com.orbit.solver.core.rule;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一条规则的参数值（来自 {@code constraint_rules.params} JSON）。
 *
 * <p>取值时对类型宽容（JSON 反序列化后数字可能是 Integer 也可能是 Long），
 * 校验时对范围严格。
 */
public record RuleParams(Map<String, Object> values) {

    public RuleParams {
        values = values == null ? Map.of() : new LinkedHashMap<>(values);
    }

    public static RuleParams empty() {
        return new RuleParams(Map.of());
    }

    public static RuleParams of(Map<String, Object> values) {
        return new RuleParams(values);
    }

    // ---------------------------------------------------------------- 取值

    public boolean has(String name) {
        return values.get(name) != null;
    }

    public long requireLong(String name) {
        Object v = values.get(name);
        if (v == null) {
            throw new IllegalArgumentException("规则参数缺失: " + name);
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("规则参数 " + name + " 不是整数: " + v);
        }
    }

    public long longOr(String name, long fallback) {
        return has(name) ? requireLong(name) : fallback;
    }

    public int requireInt(String name) {
        return Math.toIntExact(requireLong(name));
    }

    public int intOr(String name, int fallback) {
        return has(name) ? requireInt(name) : fallback;
    }

    public boolean boolOr(String name, boolean fallback) {
        Object v = values.get(name);
        if (v == null) {
            return fallback;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        return Boolean.parseBoolean(String.valueOf(v).trim());
    }

    public String stringOr(String name, String fallback) {
        Object v = values.get(name);
        return v == null ? fallback : String.valueOf(v);
    }

    // ---------------------------------------------------------------- 校验

    /** 返回错误说明列表；空列表表示通过。 */
    public List<String> validate(List<RuleParamSpec> specs) {
        List<String> errors = new ArrayList<>();
        for (RuleParamSpec spec : specs) {
            if (spec.required() && !has(spec.name())) {
                errors.add("缺少必填参数 " + spec.name() + "（" + spec.label() + "）");
                continue;
            }
            if (!has(spec.name())) {
                continue;
            }
            Object raw = values.get(spec.name());
            switch (spec.type()) {
                case INT, LONG -> {
                    if (!(raw instanceof Number)) {
                        try {
                            Long.parseLong(String.valueOf(raw).trim());
                        } catch (NumberFormatException e) {
                            errors.add("参数 " + spec.name() + " 必须是整数，实际为 " + raw);
                            continue;
                        }
                    }
                    long n = requireLong(spec.name());
                    if (spec.min() != null && n < spec.min()) {
                        errors.add("参数 " + spec.name() + " 不能小于 " + spec.min() + "，实际为 " + n);
                    }
                    if (spec.max() != null && n > spec.max()) {
                        errors.add("参数 " + spec.name() + " 不能大于 " + spec.max() + "，实际为 " + n);
                    }
                }
                case ENUM -> {
                    String s = String.valueOf(raw);
                    if (!spec.enumValues().contains(s)) {
                        errors.add("参数 " + spec.name() + " 取值必须是 "
                                + spec.enumValues() + " 之一，实际为 " + s);
                    }
                }
                case BOOL -> {
                    if (!(raw instanceof Boolean)
                            && !"true".equalsIgnoreCase(String.valueOf(raw))
                            && !"false".equalsIgnoreCase(String.valueOf(raw))) {
                        errors.add("参数 " + spec.name() + " 必须是布尔值，实际为 " + raw);
                    }
                }
                case STRING -> {
                    // 任意字符串均可
                }
            }
        }
        return errors;
    }
}
