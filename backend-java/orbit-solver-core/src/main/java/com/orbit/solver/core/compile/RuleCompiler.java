package com.orbit.solver.core.compile;

import com.google.ortools.sat.CpModel;
import com.orbit.solver.core.engine.ModelFingerprints;
import com.orbit.solver.core.engine.SlotVariableIndex;
import com.orbit.solver.core.model.ScheduleInput;
import com.orbit.solver.core.rule.ConstraintRule;
import com.orbit.solver.core.rule.PenaltyCollector;
import com.orbit.solver.core.rule.RuleContext;
import com.orbit.solver.core.rule.RuleHandler;
import com.orbit.solver.core.rule.RuleParams;
import com.orbit.solver.core.rule.RuleRegistry;

import java.util.List;

/**
 * 把「一份排课输入 + 一套规则」编译成 CP-SAT 模型。
 *
 * <p>编译顺序：
 * <ol>
 *   <li>建索引（变量在 handler 请求时才创建）</li>
 *   <li>应用<b>不变量</b>与<b>数据驱动约束</b>（{@link RuleHandler#mandatory()} 为真者）</li>
 *   <li>应用规则集里的可配置规则（按规则集顺序）</li>
 *   <li>提交目标函数</li>
 * </ol>
 *
 * <p>{@code mandatory()} 那一档表达的是「课表违反了就是错的」——例如同一教师同一时段
 * 上了两个班。它不接受用户配置，也无法被规则集关闭。
 */
public final class RuleCompiler {

    private final RuleRegistry registry;

    public RuleCompiler(RuleRegistry registry) {
        this.registry = registry;
    }

    public static RuleCompiler withBuiltinRules() {
        return new RuleCompiler(BuiltinRules.registry());
    }

    public CompiledModel compile(ScheduleInput input, List<ConstraintRule> rules) {
        CpModel model = new CpModel();
        SlotVariableIndex index = SlotVariableIndex.create(model, input);
        RuleContext ctx = new RuleContext(index);
        PenaltyCollector penalties = new PenaltyCollector(model);

        // 1) 不变量 + 数据驱动约束（硬）
        for (RuleHandler handler : registry.mandatory()) {
            handler.apply(ctx, RuleParams.empty(), true, penalties);
        }

        // 2) 可配置规则
        for (ConstraintRule rule : rules) {
            if (!rule.enabled()) {
                continue;
            }
            RuleHandler handler = registry.get(rule.type());
            if (handler.mandatory()) {
                continue;   // 不变量不接受外部配置，避免被规则表重复施加
            }
            List<String> errors = rule.params().validate(handler.params());
            if (!errors.isEmpty()) {
                throw new IllegalArgumentException(
                        "规则 " + rule.type() + " 参数非法: " + String.join("; ", errors));
            }
            penalties.useWeight(rule.effectiveWeight());
            handler.apply(ctx, rule.params(), !rule.isSoft(), penalties);
        }

        // 3) 目标
        penalties.commit();

        String stats = model.modelStats();
        return new CompiledModel(model, index, ctx, stats, ModelFingerprints.ofStats(stats));
    }
}
