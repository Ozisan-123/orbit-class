package com.orbit.solver.core.rule.handler;

import com.google.ortools.sat.BoolVar;
import com.google.ortools.sat.CpModel;
import com.google.ortools.sat.LinearExpr;
import com.orbit.solver.core.rule.PenaltyCollector;
import com.orbit.solver.core.rule.RuleContext;
import com.orbit.solver.core.model.ClassData;
import com.orbit.solver.core.rule.RuleHandler;
import com.orbit.solver.core.rule.RuleParamSpec;
import com.orbit.solver.core.rule.RuleParams;

import java.util.List;
import java.util.Map;

/**
 * 数据驱动硬约束：每科周课时数必须排满。
 *
 * <p>参数不来自规则表，而来自教务数据（{@code grade_subjects.weekly_hours}），
 * 所以标记为 {@code mandatory}、不接受外部参数。对应原 {@code engine.py} 的「硬约束 1」。
 *
 * <p>固定课已占的节数由 {@code ctx.fixedCountForSubject} 扣减 —— 规则里不再出现 fixed 记账。
 */
public final class SubjectWeeklyHoursHandler implements RuleHandler {

    public static final String TYPE = "subject_weekly_hours";

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public String displayName() {
        return "各科周课时数";
    }

    @Override
    public List<RuleParamSpec> params() {
        return List.of();
    }

    @Override
    public boolean mandatory() {
        return true;
    }

    @Override
    public String describe(RuleParams params) {
        return "每个班每科的周课时数必须排满（来自年级课时设置）";
    }

    @Override
    public void apply(RuleContext ctx, RuleParams params, boolean hard, PenaltyCollector penalties) {
        CpModel model = ctx.model();
        for (ClassData cd : ctx.classes()) {
            long classId = cd.classId();
            for (Map.Entry<Long, Integer> entry : ctx.weeklyHoursOf(classId).entrySet()) {
                long subjectId = entry.getKey();
                int needed = entry.getValue();
                int fixed = ctx.fixedCountForSubject(classId, subjectId);
                int remaining = needed - fixed;
                if (remaining <= 0) {
                    continue;   // 固定课已经满足甚至超过要求
                }
                List<BoolVar> vars = ctx.freeSubjectSlots(classId, subjectId);
                if (!vars.isEmpty()) {
                    model.addEquality(LinearExpr.sum(HandlerSupport.args(vars)), remaining);
                }
            }
        }
    }
}
