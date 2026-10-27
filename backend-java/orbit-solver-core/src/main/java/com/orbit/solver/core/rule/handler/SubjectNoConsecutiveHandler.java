package com.orbit.solver.core.rule.handler;

import com.google.ortools.sat.BoolVar;
import com.google.ortools.sat.CpModel;
import com.google.ortools.sat.Literal;
import com.orbit.solver.core.rule.PenaltyCollector;
import com.orbit.solver.core.rule.RuleContext;
import com.orbit.solver.core.model.ClassData;
import com.orbit.solver.core.rule.RuleHandler;
import com.orbit.solver.core.rule.RuleParamSpec;
import com.orbit.solver.core.rule.RuleParams;

import java.util.Collection;
import java.util.List;

/**
 * 可配置软约束：某科目不要连排两节。
 *
 * <p>{@code subject_id} 可省略 —— 省略时表示「所有科目都不连排」。
 *
 * <p>对应原 {@code engine.py} 的「软约束 2」。原实现要在规则内部手工处理
 * 「前一节是固定课 / 后一节是固定课 / 两节都不是」三种分支并自查 fixed 表；
 * 现在这些判断通过 {@code ctx.fixedSubjectAt} 完成，规则本体只剩业务逻辑。
 */
public final class SubjectNoConsecutiveHandler implements RuleHandler {

    public static final String TYPE = "subject_no_consecutive";
    public static final String P_SUBJECT = "subject_id";

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public String displayName() {
        return "单科不连排";
    }

    @Override
    public List<RuleParamSpec> params() {
        return List.of(
                RuleParamSpec.optionalInt(P_SUBJECT, "科目 ID（留空表示所有科目）", 1, Integer.MAX_VALUE));
    }

    @Override
    public String describe(RuleParams params) {
        return params.has(P_SUBJECT)
                ? "科目 " + params.longOr(P_SUBJECT, -1) + " 不连排两节"
                : "所有科目都不连排两节";
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
                for (int day = 0; day < ctx.days(); day++) {
                    int periods = ctx.periodsOn(classId, day);
                    for (int period = 0; period + 1 < periods; period++) {
                        long fixedFirst = ctx.fixedSubjectAt(classId, day, period);
                        long fixedSecond = ctx.fixedSubjectAt(classId, day, period + 1);

                        if (fixedFirst >= 0 && fixedSecond >= 0) {
                            // 两节都是固定课：是否违规与决策无关，直接成为常数惩罚
                            if (fixedFirst == subjectId && fixedSecond == subjectId) {
                                penalties.addConstant(1);
                            }
                            continue;
                        }
                        if (fixedFirst >= 0) {
                            if (fixedFirst != subjectId) {
                                continue;
                            }
                            BoolVar second = ctx.subjectAt(classId, day, period + 1, subjectId);
                            if (second != null) {
                                penalties.add(second);
                            }
                        } else if (fixedSecond >= 0) {
                            if (fixedSecond != subjectId) {
                                continue;
                            }
                            BoolVar first = ctx.subjectAt(classId, day, period, subjectId);
                            if (first != null) {
                                penalties.add(first);
                            }
                        } else {
                            BoolVar first = ctx.subjectAt(classId, day, period, subjectId);
                            BoolVar second = ctx.subjectAt(classId, day, period + 1, subjectId);
                            if (first != null && second != null) {
                                BoolVar both = model.newBoolVar(
                                        String.format("cons_%d_%d_%d_%d",
                                                classId, day, period, subjectId));
                                model.addBoolAnd(new Literal[] {first, second})
                                        .onlyEnforceIf(both);
                                model.addBoolOr(new Literal[] {first.not(), second.not()})
                                        .onlyEnforceIf(both.not());
                                penalties.add(both);
                            }
                        }
                    }
                }
            }
        }
    }
}
