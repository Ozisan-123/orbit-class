package com.orbit.solver.core.rule.handler;

import com.google.ortools.sat.BoolVar;
import com.google.ortools.sat.CpModel;
import com.google.ortools.sat.LinearExpr;
import com.orbit.solver.core.engine.ConstructionInfeasible;
import com.orbit.solver.core.rule.PenaltyCollector;
import com.orbit.solver.core.rule.RuleContext;
import com.orbit.solver.core.rule.RuleHandler;
import com.orbit.solver.core.rule.RuleParamSpec;
import com.orbit.solver.core.rule.RuleParams;

import java.util.List;

/**
 * 可配置规则：某教师在某天某节不可用（不排他的课）。
 *
 * <p>参数里的 {@code day} 与 {@code period} 都是 <b>1 开始</b>的自然语言编号
 * （「周 3 第 5 节」），与前端表单一致；内部减 1 后使用。
 *
 * <p><b>关键设计：与固定课冲突时抛 {@link ConstructionInfeasible}，而不是交给求解器。</b>
 * 因为「张三周三第 5 节不可用」和「周三第 5 节固定由张三上」是<b>配置矛盾</b>，
 * 不是排课难度 —— 交给求解器只会得到一个「无解」，用户看不懂原因。
 * 抛异常能直接告诉用户是哪条配置打架了。
 *
 * <p>硬：直接把该时段的变量全部置 0。软：把使用该时段作为惩罚（「尽量别用」）。
 */
public final class TeacherUnavailableHandler implements RuleHandler {

    public static final String TYPE = "teacher_unavailable";
    public static final String P_TEACHER = "teacher_id";
    public static final String P_DAY = "day";
    public static final String P_PERIOD = "period";

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public String displayName() {
        return "教师不可用时段";
    }

    @Override
    public List<RuleParamSpec> params() {
        return List.of(
                RuleParamSpec.intParam(P_TEACHER, "教师 ID", 1, Integer.MAX_VALUE),
                RuleParamSpec.intParam(P_DAY, "周几（1=周一）", 1, 5),
                RuleParamSpec.intParam(P_PERIOD, "第几节（1 开始）", 1, 12));
    }

    @Override
    public String describe(RuleParams params) {
        return "教师 " + params.longOr(P_TEACHER, -1)
                + " 在周 " + params.intOr(P_DAY, 1)
                + " 第 " + params.intOr(P_PERIOD, 1) + " 节不可用";
    }

    @Override
    public void apply(RuleContext ctx, RuleParams params, boolean hard, PenaltyCollector penalties) {
        CpModel model = ctx.model();
        long teacherId = params.requireLong(P_TEACHER);
        int day = params.requireInt(P_DAY) - 1;
        int period = params.requireInt(P_PERIOD) - 1;

        if (!ctx.teacherExists(teacherId)) {
            throw new ConstructionInfeasible("规则引用了不存在的教师 " + teacherId);
        }

        int fixed = ctx.fixedCountForTeacher(teacherId, day, period);
        List<BoolVar> vars = ctx.freeTeacherSlots(teacherId, day, period);

        if (hard) {
            if (fixed > 0) {
                throw new ConstructionInfeasible(
                        "教师 " + teacherId + " 在第 " + (day + 1) + " 天第 " + (period + 1)
                                + " 节既有固定课、又被设置为不可用，配置自相矛盾");
            }
            if (!vars.isEmpty()) {
                model.addEquality(LinearExpr.sum(HandlerSupport.args(vars)), 0);
            }
        } else {
            if (fixed > 0) {
                penalties.addConstant(1);
            }
            for (BoolVar v : vars) {
                penalties.add(v);
            }
        }
    }
}
