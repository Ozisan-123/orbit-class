package com.orbit.solver.core.compile;

import com.google.ortools.sat.CpModel;
import com.orbit.solver.core.engine.SlotVariableIndex;
import com.orbit.solver.core.rule.RuleContext;

/**
 * 编译产物：模型 + 建好的变量索引 + 规则上下文。
 *
 * <p>结果提取需要 {@code index}（把整数变量还原成「哪个班哪节上什么课哪位老师」），
 * 诊断需要 {@code modelStats}。
 */
public record CompiledModel(
        CpModel model,
        SlotVariableIndex index,
        RuleContext context,
        String modelStats,
        String modelFingerprint) {
}
