package com.orbit.solver.core.rule.handler;

import com.google.ortools.sat.BoolVar;
import com.google.ortools.sat.CpModel;
import com.orbit.solver.core.rule.PenaltyCollector;
import com.orbit.solver.core.rule.RuleContext;
import com.orbit.solver.core.model.ScheduleInput;
import com.orbit.solver.core.rule.RuleHandler;
import com.orbit.solver.core.rule.RuleParamSpec;
import com.orbit.solver.core.rule.RuleParams;

import java.util.List;

/**
 * 数据驱动硬约束：教师每周课时不超过上限。
 *
 * <p>上限来自 {@code teachers.max_weekly_hours}，因此 {@code mandatory}、无外部参数。
 * 对应原 {@code engine.py} 的「硬约束 3」。
 *
 * <p>它复用与「教师时段不冲突」<b>完全相同的</b>教师成员布尔（{@code ctx.freeTeacherSlots}），
 * 因此不再产生原实现里那套重复变量。
 */
public final class TeacherWeeklyLimitHandler implements RuleHandler {

    public static final String TYPE = "teacher_max_weekly_hours";

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public String displayName() {
        return "教师周课时上限";
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
        return "教师每周课时不超过其上限（来自教师档案）";
    }

    @Override
    public void apply(RuleContext ctx, RuleParams params, boolean hard, PenaltyCollector penalties) {
        CpModel model = ctx.model();
        for (ScheduleInput.TeacherInfo teacher : ctx.teachers()) {
            long teacherId = teacher.id();
            List<BoolVar> vars = ctx.freeTeacherSlots(teacherId);
            int fixed = ctx.fixedCountForTeacher(teacherId);
            if (!vars.isEmpty()) {
                model.addLessOrEqual(HandlerSupport.sumPlus(vars, fixed),
                        teacher.maxWeeklyHours());
            }
        }
    }
}
