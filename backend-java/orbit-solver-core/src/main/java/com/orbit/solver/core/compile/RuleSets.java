package com.orbit.solver.core.compile;

import com.orbit.solver.core.rule.ConstraintRule;
import com.orbit.solver.core.rule.RuleParams;
import com.orbit.solver.core.rule.handler.SubjectMaxPerDayHandler;
import com.orbit.solver.core.rule.handler.SubjectNoConsecutiveHandler;

import java.util.List;
import java.util.Map;

/**
 * 规则集**预设** —— 直接可用的成套规则。
 *
 * <h2>{@link #legacyEquivalent()} 是 P1 迁移期的关键</h2>
 * Python 侧的约束是**硬编码在 {@code scheduler/engine.py} 里**的（硬 1/2/3 + 软 1/2），
 * 而 Java 侧已经改造成「规则系统」。要让两边产出**同一个模型**，就必须有一份
 * 「语义上等价于那 5 条硬编码约束」的规则集 —— 就是这个方法。
 *
 * <p>映射关系：
 * <table>
 *   <tr><th>Python</th><th>这里</th><th>怎么生效</th></tr>
 *   <tr><td>硬 1 教师不撞课</td><td>{@code TeacherNoConflict}</td><td>mandatory，自动施加</td></tr>
 *   <tr><td>硬 2 周课时必须排满</td><td>{@code SubjectWeeklyHours}</td><td>mandatory，自动施加</td></tr>
 *   <tr><td>硬 3 教师周课时上限</td><td>{@code TeacherWeeklyLimit}</td><td>mandatory，自动施加</td></tr>
 *   <tr><td>软 1 同一科目一天最多 1 节</td><td>{@code SubjectMaxPerDay(max=1)}</td><td><b>本方法显式列出</b></td></tr>
 *   <tr><td>软 2 同一科目不连排</td><td>{@code SubjectNoConsecutive}</td><td><b>本方法显式列出</b></td></tr>
 * </table>
 * 前三条是「不变量/数据驱动」，由 {@code RuleCompiler} 按 {@code mandatory()} 自动施加，
 * 所以这里只列后两条软规则（权重都为 1，与 Python 一致）。
 *
 * <h2>为什么它必须在 <b>main</b> 而不是 test 里</h2>
 * 迁移期的 scheduling 服务要用它来求解，等价性测试也要用它来对基线 ——
 * 如果各写一份，两边迟早漂移，而「漂移」正是这个预设要防的东西。
 * 所以：**只有一份定义，测试引用生产的**。
 */
public final class RuleSets {

    /**
     * 与现有 Python 行为等价的一套规则（权重各 1）。
     *
     * <p>⚠ 改动它会让模型指纹变化 —— {@code GoldenModelTest} 会立刻红，
     * 那是**有意的**：模型变了必须被看见并重新解释，而不是悄悄变。
     */
    public static List<ConstraintRule> legacyEquivalent() {
        return List.of(
                ConstraintRule.soft(SubjectMaxPerDayHandler.TYPE,
                        RuleParams.of(Map.of("max", 1)), 1, "任何科目一天最多 1 节"),
                ConstraintRule.soft(SubjectNoConsecutiveHandler.TYPE,
                        RuleParams.empty(), 1, "任何科目不连排两节"));
    }

    private RuleSets() {
    }
}
