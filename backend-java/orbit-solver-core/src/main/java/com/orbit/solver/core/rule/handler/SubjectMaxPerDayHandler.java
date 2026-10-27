package com.orbit.solver.core.rule.handler;

import com.google.ortools.sat.BoolVar;
import com.google.ortools.sat.CpModel;
import com.google.ortools.sat.IntVar;
import com.google.ortools.sat.LinearExpr;
import com.orbit.solver.core.rule.PenaltyCollector;
import com.orbit.solver.core.rule.RuleContext;
import com.orbit.solver.core.engine.ConstructionInfeasible;
import com.orbit.solver.core.model.ClassData;
import com.orbit.solver.core.rule.RuleHandler;
import com.orbit.solver.core.rule.RuleParamSpec;
import com.orbit.solver.core.rule.RuleParams;

import java.util.Collection;
import java.util.List;

/**
 * 可配置规则：某科目在一个班一天内最多上几节。
 *
 * <p>{@code subject_id} 可省略 —— 省略时表示「适用于所有科目」，
 * 因此既能表达「语文一天最多两节」，也能表达「任何科目一天都别超过一节」。
 *
 * <p>硬软两用：
 * <ul>
 *   <li>硬：{@code sum(该科当天时段) + 固定课数 ≤ max}</li>
 *   <li>软：惩罚 {@code max(0, 实际节数 - max)}（单向下界线性化）</li>
 * </ul>
 * 软约束在 {@code max = 1} 时等价于原 {@code engine.py} 的「软约束 1」。
 */
public final class SubjectMaxPerDayHandler implements RuleHandler {

    public static final String TYPE = "subject_max_per_day";
    public static final String P_SUBJECT = "subject_id";
    public static final String P_MAX = "max";

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public String displayName() {
        return "单日单科节数上限";
    }

    @Override
    public List<RuleParamSpec> params() {
        return List.of(
                RuleParamSpec.optionalInt(P_SUBJECT, "科目 ID（留空表示所有科目）", 1, Integer.MAX_VALUE),
                RuleParamSpec.intParam(P_MAX, "一天最多几节", 1, 20));
    }

    @Override
    public String describe(RuleParams params) {
        String scope = params.has(P_SUBJECT)
                ? "科目 " + params.longOr(P_SUBJECT, -1)
                : "所有科目";
        return scope + " 一天最多 " + params.intOr(P_MAX, 1) + " 节";
    }

    @Override
    public void apply(RuleContext ctx, RuleParams params, boolean hard, PenaltyCollector penalties) {
        CpModel model = ctx.model();
        Long only = params.has(P_SUBJECT) ? params.requireLong(P_SUBJECT) : null;
        int max = params.requireInt(P_MAX);

        for (ClassData cd : ctx.classes()) {
            long classId = cd.classId();
            Collection<Long> subjects = only != null ? List.of(only) : ctx.subjectsOf(classId);
            for (long subjectId : subjects) {
                if (!ctx.subjectsOf(classId).contains(subjectId)) {
                    continue;
                }
                for (int day = 0; day < ctx.days(); day++) {
                    int fixed = ctx.fixedCountForSubjectOnDay(classId, subjectId, day);
                    List<BoolVar> vars = ctx.freeSubjectSlotsOnDay(classId, subjectId, day);

                    if (hard) {
                        if (fixed > max) {
                            throw new ConstructionInfeasible(
                                    "班级 " + classId + " 在第 " + (day + 1) + " 天，科目 "
                                            + subjectId + " 的固定课已有 " + fixed
                                            + " 节，超过上限 " + max);
                        }
                        if (!vars.isEmpty()) {
                            model.addLessOrEqual(HandlerSupport.sumPlus(vars, fixed), max);
                        }
                    } else {
                        int totalPossible = fixed + vars.size();
                        if (totalPossible <= max) {
                            continue;   // 不可能超，无需惩罚
                        }
                        IntVar excess = model.newIntVar(0, totalPossible,
                                String.format("day_exc_%d_%d_%d", classId, subjectId, day));
                        model.addGreaterOrEqual(excess, LinearExpr.newBuilder()
                                .addSum(HandlerSupport.args(vars))
                                .add(fixed - max)
                                .build());
                        penalties.add(excess);
                    }
                }
            }
        }
    }
}
