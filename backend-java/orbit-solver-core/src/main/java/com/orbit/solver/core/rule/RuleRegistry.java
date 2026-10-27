package com.orbit.solver.core.rule;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 规则类型注册表：{@code rule_type -> RuleHandler}。
 *
 * <p>它是一个纯粹的数据结构 —— <b>刻意不知道有哪些内置规则</b>。
 * 「系统内置哪几种规则」属于装配知识，由 {@code compile.BuiltinRules} 负责构造，
 * 否则本包与 {@code rule.handler} 之间会形成双向依赖（注册表 new 实现、实现 implements 接口）。
 *
 * <p>注册顺序即变量创建顺序，因此内部保序（LinkedHashMap）。
 */
public final class RuleRegistry {

    private final Map<String, RuleHandler> byType = new LinkedHashMap<>();

    public RuleRegistry(List<RuleHandler> handlers) {
        for (RuleHandler h : handlers) {
            RuleHandler previous = byType.put(h.type(), h);
            if (previous != null) {
                throw new IllegalStateException("规则类型重复注册: " + h.type());
            }
        }
    }

    public RuleHandler get(String type) {
        RuleHandler h = byType.get(type);
        if (h == null) {
            throw new IllegalArgumentException("未知的规则类型: " + type
                    + "（已注册: " + byType.keySet() + "）");
        }
        return h;
    }

    public boolean contains(String type) {
        return byType.containsKey(type);
    }

    public Collection<RuleHandler> all() {
        return byType.values();
    }

    public List<RuleHandler> mandatory() {
        List<RuleHandler> out = new ArrayList<>();
        for (RuleHandler h : byType.values()) {
            if (h.mandatory()) {
                out.add(h);
            }
        }
        return out;
    }

    /** 可配置的规则（能出现在规则列表里让用户编辑的）。 */
    public List<RuleHandler> configurable() {
        List<RuleHandler> out = new ArrayList<>();
        for (RuleHandler h : byType.values()) {
            if (!h.mandatory()) {
                out.add(h);
            }
        }
        return out;
    }
}
