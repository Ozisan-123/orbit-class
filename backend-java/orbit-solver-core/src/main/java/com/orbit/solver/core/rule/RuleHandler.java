package com.orbit.solver.core.rule;

import com.orbit.solver.core.rule.PenaltyCollector;
import com.orbit.solver.core.rule.RuleContext;

import java.util.List;

/**
 * 一种规则的实现。
 *
 * <p>规则系统是「数据 + 实现」两级结构：用户编辑的是 {@code constraint_rules} 表里的
 * {@code rule_type} 与 {@code params}（数据），而 {@code rule_type} 指向的实现由开发者写一次。
 *
 * <p>{@link #mandatory()} 用来表达**不变量**：像「同一教师同一时段不能上两个班」这种，
 * 课表违反了就是错的，不允许用户关掉，因此它始终生效、不接受参数。
 * 数据驱动的约束（周课时数、教师周上限）也走这一档，参数直接从快照读。
 */
public interface RuleHandler {

    /** 稳定标识，会写进数据库的 {@code rule_type} 列。 */
    String type();

    /** 面向用户的中文名。 */
    String displayName();

    /** 参数声明 —— 同时供后端校验、前端表单、助手工具定义使用。 */
    List<RuleParamSpec> params();

    /** 不变量：始终生效，不可被规则集关闭。 */
    default boolean mandatory() {
        return false;
    }

    /** 面向用户的一句话说明（规则列表里显示）。 */
    default String describe(RuleParams params) {
        return displayName();
    }

    /**
     * 把这条规则编译进模型。
     *
     * @param hard      true = 硬约束（加约束），false = 软约束（加惩罚项）
     * @param penalties 软约束的惩罚项收集器；hard 为 true 时不应使用
     */
    void apply(RuleContext ctx, RuleParams params, boolean hard, PenaltyCollector penalties);
}
