package com.orbit.scheduling.rule;

import com.orbit.common.error.BusinessException;
import com.orbit.solver.core.compile.BuiltinRules;
import com.orbit.solver.core.rule.RuleHandler;
import com.orbit.solver.core.rule.RuleRegistry;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 规则类型目录 —— 规则系统的"能力清单"，也是**唯一的真相来源**。
 *
 * <h2>它为什么重要：一份 schema，三处消费</h2>
 * <pre>
 *   RuleHandler.params()  →  RuleParamSpec  →  ┬─→ 前端"新建规则"表单（动态渲染，不用为每条规则写页面）
 *                                              ├─→ 后端保存时的参数校验
 *                                              └─→ 助手的工具定义（P4/P5，新增规则类型自动可用）
 * </pre>
 * 这三处**必须来自同一个地方**。如果前端自己维护一份表单定义，
 * 那么"开发者加了一条新规则"和"前端能配这条规则"就会变成两件事 ——
 * 而它们本该是同一件事。
 *
 * <h2>为什么直接用 {@link BuiltinRules#registry()} 而不是再列一份</h2>
 * {@code BuiltinRules} 已经是"系统内置哪几种规则"的装配点（solver-core 里也这么用它）。
 * 这里的职责只是**换个形状暴露出去**（投影成 HTTP 响应用的 DTO），
 * 而不是决定有哪些规则。所以它没有任何 if/else 分支，也没有类型白名单 ——
 * 新增一个 handler，这个接口下次请求就自动多一条。
 */
@Component
public class RuleTypeCatalog {

    private final RuleRegistry registry = BuiltinRules.registry();

    /** 全部规则类型（含不变量）—— 供"规则能力清单"接口使用。 */
    public List<RuleTypeResponse> all() {
        return registry.all().stream().map(RuleTypeResponse::of).toList();
    }

    /**
     * 只有**可配置**的类型 —— 供"新建规则"下拉使用。
     *
     * <p>不变量与数据驱动规则**不该出现在这里**：它们始终生效、不接受参数，
     * 让用户"选它"只会产生一条什么也不做的规则（甚至误导用户以为关掉它就能改变行为）。
     */
    public List<RuleTypeResponse> configurable() {
        return registry.configurable().stream().map(RuleTypeResponse::of).toList();
    }

    /**
     * 按 type 找 handler。
     *
     * <p>⚠ 「未知类型」是**用户输入错误**，不是服务端故障 —— 所以在这里就把
     * solver-core 抛的 {@link IllegalArgumentException} 翻成 400。
     *
     * <p>为什么不直接在全局异常处理器里把 {@code IllegalArgumentException} 映射成 400：
     * 那太宽了，会把真正的编程错误（空指针式的前置条件违反）也报成"你的请求有问题"，
     * 从此这类 bug 永远以 400 的面目出现、淹没在正常错误里。
     * **在边界上翻译，而不是在出口处兜底。**
     */
    public RuleHandler handlerOf(String ruleType) {
        if (!registry.contains(ruleType)) {
            throw BusinessException.badRequest("未知的规则类型: " + ruleType);
        }
        return registry.get(ruleType);
    }

    public boolean isKnown(String ruleType) {
        return registry.contains(ruleType);
    }

    public boolean isMandatory(String ruleType) {
        return registry.contains(ruleType) && registry.get(ruleType).mandatory();
    }
}
