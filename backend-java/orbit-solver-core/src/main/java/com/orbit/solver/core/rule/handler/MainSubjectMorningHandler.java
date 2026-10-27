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
 * 可配置规则：主科优先安排在上午。
 *
 * <p>参数 {@code morning_periods} 是「上午到第几节」的<b>绝对节数</b>
 * （例如 4 表示前 4 节算上午）。做成参数而不是硬编码，是因为各校作息不同：
 * 有的学校上午 4 节、有的 5 节，还有半天制的。
 *
 * <p>为什么用绝对节数而不是「按各班节数取一半」：约束的语义应该由学校说了算，
 * 而不是由算法猜。各班节数不同时，「一半」会让不同年级的上午长度不一致，
 * 反而制造出难以解释的课表。
 *
 * <p>硬软两用：硬 → 主科一律不许排在下午；软 → 排到下午就惩罚（更常见的用法）。
 */
public final class MainSubjectMorningHandler implements RuleHandler {

    public static final String TYPE = "main_subject_morning";
    public static final String P_SUBJECT = "subject_id";
    public static final String P_MORNING = "morning_periods";

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public String displayName() {
        return "主科优先上午";
    }

    @Override
    public List<RuleParamSpec> params() {
        return List.of(
                RuleParamSpec.optionalInt(P_SUBJECT, "科目 ID（留空表示所有科目）",
                        1, Integer.MAX_VALUE),
                RuleParamSpec.intParam(P_MORNING, "上午到第几节（前 N 节算上午）", 1, 12));
    }

    @Override
    public String describe(RuleParams params) {
        String what = params.has(P_SUBJECT)
                ? "科目 " + params.longOr(P_SUBJECT, -1)
                : "所有科目";
        return what + " 尽量安排在前 " + params.intOr(P_MORNING, 4) + " 节（上午）";
    }

    @Override
    public void apply(RuleContext ctx, RuleParams params, boolean hard, PenaltyCollector penalties) {
        CpModel model = ctx.model();
        Long only = params.has(P_SUBJECT) ? params.requireLong(P_SUBJECT) : null;
        int morningPeriods = params.requireInt(P_MORNING);

        for (ClassData cd : ctx.classes()) {
            long classId = cd.classId();
            Collection<Long> subjects = only != null ? List.of(only) : ctx.subjectsOf(classId);
            for (long subjectId : subjects) {
                if (!ctx.subjectsOf(classId).contains(subjectId)) {
                    continue;
                }
                List<BoolVar> afternoonVars = new ArrayList<>();
                int fixedViolations = 0;

                for (int day = 0; day < ctx.days(); day++) {
                    int periods = ctx.periodsOn(classId, day);
                    for (int period = morningPeriods; period < periods; period++) {
                        long fixedSubject = ctx.fixedSubjectAt(classId, day, period);
                        if (fixedSubject >= 0) {
                            if (fixedSubject == subjectId) {
                                fixedViolations++;
                            }
                            continue;
                        }
                        BoolVar b = ctx.subjectAt(classId, day, period, subjectId);
                        if (b != null) {
                            afternoonVars.add(b);
                        }
                    }
                }

                if (hard) {
                    if (fixedViolations > 0) {
                        throw new ConstructionInfeasible(
                                "班级 " + classId + " 有 " + fixedViolations
                                        + " 节把科目 " + subjectId
                                        + " 固定在下午，与「主科不排下午」冲突");
                    }
                    if (!afternoonVars.isEmpty()) {
                        model.addEquality(LinearExpr.sum(HandlerSupport.args(afternoonVars)), 0);
                    }
                } else {
                    if (fixedViolations > 0) {
                        penalties.addConstant(fixedViolations);
                    }
                    for (BoolVar b : afternoonVars) {
                        penalties.add(b);
                    }
                }
            }
        }
    }
}
