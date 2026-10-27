package com.orbit.solver.core.rule;

/**
 * 一条生效的规则实例 —— 对应 {@code constraint_rules} 表里的一行。
 *
 * <p>scheduling 服务负责把实体映射成这个结构，求解进程只认这个；两边不共享实体类。
 */
public record ConstraintRule(
        String type,
        RuleParams params,
        Hardness hardness,
        int weight,
        boolean enabled,
        String description) {

    public enum Hardness {
        HARD, SOFT
    }

    public static ConstraintRule hard(String type, RuleParams params, String description) {
        return new ConstraintRule(type, params, Hardness.HARD, 1, true, description);
    }

    public static ConstraintRule soft(String type, RuleParams params, int weight,
                                      String description) {
        return new ConstraintRule(type, params, Hardness.SOFT, weight, true, description);
    }

    public boolean isSoft() {
        return hardness == Hardness.SOFT;
    }

    public int effectiveWeight() {
        return weight <= 0 ? 1 : weight;
    }
}
