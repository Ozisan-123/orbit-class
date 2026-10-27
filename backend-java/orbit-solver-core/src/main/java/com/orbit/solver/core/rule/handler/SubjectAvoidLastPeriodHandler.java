package com.orbit.solver.core.rule.handler;

import com.google.ortools.sat.BoolVar;
import com.google.ortools.sat.CpModel;
import com.google.ortools.sat.LinearExpr;
import com.orbit.solver.core.engine.ConstructionInfeasible;
import com.orbit.solver.core.model.ClassData;
import com.orbit.solver.core.rule.PenaltyCollector;
import com.orbit.solver.core.rule.RuleContext;
import com.orbit.solver.core.rule.RuleHandler;
import com.orbit.solver.core.rule.RuleParamSpec;
import com.orbit.solver.core.rule.RuleParams;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 可配置规则：某科目不排在当天最后一节。
 *
 * <p>{@code subject_id} 可省略 —— 省略时表示「所有科目」。
 *
 * <p><b>「最后一节」必须按班按天算</b>（{@code ctx.periodsOn(classId, day) - 1}）。
 * 不同年级每天节数可以不同，低年级可能只有 6 节、高年级 8 节，
 * 把「最后一节」写成常数（例如永远取第 8 节）会在低年级上完全失效 ——
 * 而且失效得很安静：约束照加，只是加错了位置。
 *
 * <p>硬软两用：硬 → 最后一节不许是该科目；软 → 排到最后一节就惩罚。
 */
public final class SubjectAvoidLastPeriodHandler implements RuleHandler {

    public static final String TYPE = "subject_avoid_last_period";
    public static final String P_SUBJECT = "subject_id";

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public String displayName() {
        return "单科不排最后一节";
    }

    @Override
    public List<RuleParamSpec> params() {
        return List.of(
                RuleParamSpec.optionalInt(P_SUBJECT, "科目 ID（留空表示所有科目）",
                        1, Integer.MAX_VALUE));
    }

    @Override
    public String describe(RuleParams params) {
        return params.has(P_SUBJECT)
                ? "科目 " + params.longOr(P_SUBJECT, -1) + " 不排在当天最后一节"
                : "所有科目都不排在当天最后一节";
    }

    @Override
    public void apply(RuleContext ctx, RuleParams params, boolean hard, PenaltyCollector penalties) {
        CpModel model = ctx.model();
        Long only = params.has(P_SUBJECT) ? params.requireLong(P_SUBJECT) : null;

        for (ClassData cd : ctx.classes()) {
            long classId = cd.classId();
            Collection<Long> subjects = only != null ? List.of(only) : ctx.subjectsOf(classId);
            for (long subjectId : subjects) {
                if (!ctx.subjectsOf(classId).contains(subjectId)) {
                    continue;
                }
                List<BoolVar> lastPeriodVars = new ArrayList<>();
                int fixedViolations = 0;

                for (int day = 0; day < ctx.days(); day++) {
                    int periods = ctx.periodsOn(classId, day);
                    if (periods <= 0) {
                        continue;
                    }
                    int last = periods - 1;
                    long fixedSubject = ctx.fixedSubjectAt(classId, day, last);
                    if (fixedSubject >= 0) {
                        if (fixedSubject == subjectId) {
                            fixedViolations++;      // 固定课就摆在最后一节，与决策无关
                        }
                        continue;
                    }
                    BoolVar b = ctx.subjectAt(classId, day, last, subjectId);
                    if (b != null) {
                        lastPeriodVars.add(b);
                    }
                }

                if (hard) {
                    if (fixedViolations > 0) {
                        throw new ConstructionInfeasible(
                                "班级 " + classId + " 有 " + fixedViolations
                                        + " 天把科目 " + subjectId
                                        + " 固定在最后一节，与「不排最后一节」冲突");
                    }
                    if (!lastPeriodVars.isEmpty()) {
                        model.addEquality(LinearExpr.sum(HandlerSupport.args(lastPeriodVars)), 0);
                    }
                } else {
                    if (fixedViolations > 0) {
                        penalties.addConstant(fixedViolations);
                    }
                    for (BoolVar b : lastPeriodVars) {
                        penalties.add(b);
                    }
                }
            }
        }
    }
}
