package com.orbit.solver.core.rule.handler;

import com.google.ortools.sat.BoolVar;
import com.google.ortools.sat.CpModel;
import com.orbit.solver.core.rule.PenaltyCollector;
import com.orbit.solver.core.rule.RuleContext;
import com.orbit.solver.core.engine.ConstructionInfeasible;
import com.orbit.solver.core.model.ScheduleInput;
import com.orbit.solver.core.rule.RuleHandler;
import com.orbit.solver.core.rule.RuleParamSpec;
import com.orbit.solver.core.rule.RuleParams;

import java.util.List;

/**
 * 不变量：同一教师同一时段最多一节课。
 *
 * <p>课表里出现教师撞课就是错的，因此这条<b>不可配置、不可关闭</b>（{@code mandatory}）。
 * 对应原 {@code engine.py} 的「硬约束 2」。
 */
public final class TeacherNoConflictHandler implements RuleHandler {

    public static final String TYPE = "teacher_no_conflict";

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public String displayName() {
        return "教师时段不冲突";
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
        return "同一教师同一时段最多一节课（系统不变量，始终生效）";
    }

    @Override
    public void apply(RuleContext ctx, RuleParams params, boolean hard, PenaltyCollector penalties) {
        CpModel model = ctx.model();
        for (ScheduleInput.TeacherInfo teacher : ctx.teachers()) {
            long teacherId = teacher.id();
            for (int day = 0; day < ctx.days(); day++) {
                int maxPeriods = HandlerSupport.maxPeriodsOnDay(ctx, day);
                for (int period = 0; period < maxPeriods; period++) {
                    // 该教师在这个时段跨各班的布尔；固定课已由 ctx 计数，不在此列
                    List<BoolVar> vars = ctx.freeTeacherSlots(teacherId, day, period);
                    int fixed = ctx.fixedCountForTeacher(teacherId, day, period);
                    if (fixed > 1) {
                        throw new ConstructionInfeasible(
                                "教师 " + teacherId + "（" + teacher.name() + "）在第 "
                                        + (day + 1) + " 天第 " + (period + 1)
                                        + " 节被固定课占了 " + fixed + " 次，固定课之间就冲突");
                    }
                    if (!vars.isEmpty()) {
                        model.addLessOrEqual(HandlerSupport.sumPlus(vars, fixed), 1);
                    }
                }
            }
        }
    }
}
