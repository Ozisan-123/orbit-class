package com.orbit.solver.core.rule.handler;

import com.google.ortools.sat.BoolVar;
import com.google.ortools.sat.CpModel;
import com.google.ortools.sat.IntVar;
import com.orbit.solver.core.engine.ConstructionInfeasible;
import com.orbit.solver.core.model.ScheduleInput;
import com.orbit.solver.core.rule.PenaltyCollector;
import com.orbit.solver.core.rule.RuleContext;
import com.orbit.solver.core.rule.RuleHandler;
import com.orbit.solver.core.rule.RuleParamSpec;
import com.orbit.solver.core.rule.RuleParams;

import java.util.List;

/**
 * 可配置规则：某教师一天最多上几节。
 *
 * <p>{@code teacher_id} 可省略 —— 省略时表示「适用于所有教师」。
 *
 * <p>与 {@code teacher_max_weekly_hours} 的区别是粒度：那条是整周上限（数据驱动、
 * 不可配置），这条是<b>日上限</b>（可配置）。日上限是学校常见的诉求：
 * 「同一个老师一天不要排超过 4 节」。
 *
 * <p>硬软两用：硬 → {@code sum(当天时段) + 固定课数 ≤ max}；软 → 惩罚超出部分。
 */
public final class TeacherMaxDailyPeriodsHandler implements RuleHandler {

    public static final String TYPE = "teacher_max_daily_periods";
    public static final String P_TEACHER = "teacher_id";
    public static final String P_MAX = "max";

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public String displayName() {
        return "教师单日节数上限";
    }

    @Override
    public List<RuleParamSpec> params() {
        return List.of(
                RuleParamSpec.optionalInt(P_TEACHER, "教师 ID（留空表示所有教师）",
                        1, Integer.MAX_VALUE),
                RuleParamSpec.intParam(P_MAX, "一天最多几节", 1, 20));
    }

    @Override
    public String describe(RuleParams params) {
        String who = params.has(P_TEACHER)
                ? "教师 " + params.longOr(P_TEACHER, -1)
                : "所有教师";
        return who + " 一天最多 " + params.intOr(P_MAX, 1) + " 节";
    }

    @Override
    public void apply(RuleContext ctx, RuleParams params, boolean hard, PenaltyCollector penalties) {
        CpModel model = ctx.model();
        Long only = params.has(P_TEACHER) ? params.requireLong(P_TEACHER) : null;
        int max = params.requireInt(P_MAX);

        for (ScheduleInput.TeacherInfo teacher : ctx.teachers()) {
            long teacherId = teacher.id();
            if (only != null && only != teacherId) {
                continue;
            }
            for (int day = 0; day < ctx.days(); day++) {
                int fixed = ctx.fixedCountForTeacherOnDay(teacherId, day);
                List<BoolVar> vars = ctx.freeTeacherSlotsOnDay(teacherId, day);

                if (hard) {
                    if (fixed > max) {
                        throw new ConstructionInfeasible(
                                "教师 " + teacherId + "（" + teacher.name() + "）在第 "
                                        + (day + 1) + " 天的固定课已有 " + fixed
                                        + " 节，超过单日上限 " + max);
                    }
                    if (!vars.isEmpty()) {
                        model.addLessOrEqual(HandlerSupport.sumPlus(vars, fixed), max);
                    }
                } else {
                    int totalPossible = fixed + vars.size();
                    if (totalPossible <= max) {
                        continue;       // 不可能超，无需惩罚
                    }
                    IntVar excess = model.newIntVar(0, totalPossible,
                            String.format("tday_exc_%d_%d", teacherId, day));
                    model.addGreaterOrEqual(excess,
                            HandlerSupport.sumPlus(vars, fixed - max));
                    penalties.add(excess);
                }
            }
        }
    }
}
