package com.orbit.scheduling.rule;

import com.orbit.solver.core.rule.RuleHandler;

import java.util.List;

/**
 * 一种规则类型的完整说明 —— 驱动前端的"新建规则"表单。
 *
 * <h2>为什么没有 {@code layer}（不变量 / 数据驱动 / 可配置）字段</h2>
 * 方案 §6.1 的三层语义很重要，但它**在代码里唯一可判定的后果就是
 * {@link #mandatory()}**：
 * <ul>
 *   <li>不变量 + 数据驱动 → {@code mandatory = true} → 用户**碰不到**（不出现在可选类型里）</li>
 *   <li>可配置 → {@code mandatory = false} → 用户可增删改</li>
 * </ul>
 * 而"某个 mandatory 规则到底是不变量还是数据驱动"**无法从实现推出来**
 * （两者都是"参数为空、始终生效"）。与其在 scheduling 里再硬编码一张映射表
 * （那就出现了第二个真相，新增 handler 时会静默漏掉），不如**只暴露代码里真实存在的那个属性**。
 * 这个区分留给 `display_name` 与 `describe()` 用人话表达。
 *
 * <p>同理没有 {@code supports_hard}：{@code RuleHandler.apply} 本身就接受 {@code hard} 参数，
 * 任何可配置规则都能当硬约束用。"某条规则只能软"是设计建议，不是代码约束 ——
 * 暴露一个代码里不存在的限制会误导前端。
 */
public record RuleTypeResponse(
        String ruleType,
        String displayName,
        boolean mandatory,
        /** {@code !mandatory} —— 前端据此决定是否放进"可选规则类型"下拉。 */
        boolean editable,
        /** 该类规则**不需要参数**时为空列表（例如不变量）。 */
        List<RuleParamResponse> params) {

    public static RuleTypeResponse of(RuleHandler handler) {
        return new RuleTypeResponse(
                handler.type(),
                handler.displayName(),
                handler.mandatory(),
                !handler.mandatory(),
                handler.params().stream().map(RuleParamResponse::of).toList());
    }
}
