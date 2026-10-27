package com.orbit.solver.core.compile;

import com.orbit.solver.core.rule.RuleRegistry;
import com.orbit.solver.core.rule.handler.MainSubjectMorningHandler;
import com.orbit.solver.core.rule.handler.SubjectAvoidLastPeriodHandler;
import com.orbit.solver.core.rule.handler.SubjectMaxPerDayHandler;
import com.orbit.solver.core.rule.handler.SubjectNoConsecutiveHandler;
import com.orbit.solver.core.rule.handler.SubjectWeeklyHoursHandler;
import com.orbit.solver.core.rule.handler.TeacherMaxDailyPeriodsHandler;
import com.orbit.solver.core.rule.handler.TeacherMinimizeGapsHandler;
import com.orbit.solver.core.rule.handler.TeacherNoConflictHandler;
import com.orbit.solver.core.rule.handler.TeacherUnavailableHandler;
import com.orbit.solver.core.rule.handler.TeacherWeeklyLimitHandler;

import java.util.List;

/**
 * 系统内置规则的装配点。
 *
 * <p>「有哪些内置规则」属于<b>装配知识</b>，而不是 {@link RuleRegistry} 的职责 ——
 * 注册表只管「type 到实现」的映射。把这一步放在 compile 里，
 * 既让 {@code rule} 包保持为纯 API（不依赖 {@code rule.handler}），
 * 也让「新增一种规则」只有一处需要改：
 * 写一个 handler + 在这里登记一行，前端表单与助手工具会自动跟上。
 *
 * <p><b>注册顺序 = 变量创建顺序</b>，所以下面顺序有意义。前三条是不变量/数据驱动，
 * 它们按 {@code mandatory()} 被自动施加，其相对顺序决定了模型里变量的创建次序 ——
 * 改动顺序会让模型指纹变化。后七条是可配置规则，只在规则集显式引用时才生效。
 */
public final class BuiltinRules {

    /** 内置的 10 种规则：3 个不变量/数据驱动 + 7 个可配置。 */
    public static RuleRegistry registry() {
        return new RuleRegistry(List.of(
                // —— 不变量 / 数据驱动（mandatory，始终生效，顺序敏感）——
                new TeacherNoConflictHandler(),
                new SubjectWeeklyHoursHandler(),
                new TeacherWeeklyLimitHandler(),
                // —— 可配置（仅当规则集引用时生效）——
                new SubjectMaxPerDayHandler(),
                new SubjectNoConsecutiveHandler(),
                new TeacherMaxDailyPeriodsHandler(),
                new TeacherUnavailableHandler(),
                new SubjectAvoidLastPeriodHandler(),
                new MainSubjectMorningHandler(),
                new TeacherMinimizeGapsHandler()));
    }

    private BuiltinRules() {
    }
}
