package com.orbit.scheduling.rule;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * 新建规则 —— {@code POST /api/rules}。
 *
 * <p>{@code params} 是**动态**的（不同 rule_type 参数不同），所以它的校验**不能**用注解表达，
 * 只能交给 solver-core 的 {@code RuleParamSpec} schema —— 见
 * {@link RuleSetService#validateParams}。
 *
 * <p>⚠ 因此这里只声明"请求形状"层面的约束（必填、长度），
 * 而"参数值是否合法"是**业务校验**，返回 400 而不是 422：
 * 422 在这个项目里表示"请求体形状不对"（响应体是 Pydantic 风格的列表），
 * 而"语文一天最多 3 节"这种规则语义问题应当是一个能直接展示给人看的字符串。
 */
public record RuleCreateRequest(
        @NotBlank(message = "Field required")
        String ruleType,

        Map<String, Object> params,

        /** {@code hard} | {@code soft}；不传默认 {@code hard}（更保守：先当硬约束）。 */
        String hardness,

        /** 仅 soft 有效；不传默认 1。 */
        Integer weight,

        /**
         * 作用域，P2 只接受 {@code org}（不传即 org）。
         *
         * <p>结构上已经支持 grade/class/teacher/subject，但**求解只用 org 级** ——
         * 细粒度作用域要先解决"同一条规则在多个 scope 上叠加"的语义，见 §15 记录。
         */
        String scope,

        Long scopeId,

        /** 不传默认 true（新建即启用）。 */
        Boolean enabled,

        @Size(max = 255, message = "Field required")
        String description) {
}
