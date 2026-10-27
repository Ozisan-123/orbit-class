package com.orbit.solver.core.rule.handler;

import com.google.ortools.sat.BoolVar;
import com.google.ortools.sat.CpModel;
import com.google.ortools.sat.LinearArgument;
import com.google.ortools.sat.LinearExpr;
import com.google.ortools.sat.LinearExprBuilder;
import com.google.ortools.sat.Literal;

import java.util.List;

/** handler 共用的线性表达式与布尔编码工具。 */
final class HandlerSupport {

    private HandlerSupport() {
    }

    static LinearArgument[] args(List<BoolVar> vars) {
        return vars.toArray(new LinearArgument[0]);
    }

    static Literal[] literals(List<BoolVar> vars) {
        return vars.toArray(new Literal[0]);
    }

    static Literal[] negations(List<BoolVar> vars) {
        Literal[] out = new Literal[vars.size()];
        for (int i = 0; i < vars.size(); i++) {
            out[i] = vars.get(i).not();
        }
        return out;
    }

    /** sum(vars) + extra。 */
    static LinearExpr sumPlus(List<BoolVar> vars, long extra) {
        LinearExprBuilder builder = LinearExpr.newBuilder();
        if (!vars.isEmpty()) {
            builder.addSum(args(vars));
        }
        if (extra != 0) {
            builder.add(extra);
        }
        return builder.build();
    }

    /** 某一天里所有班级中最大的节数（用于遍历「天 × 节」）。 */
    static int maxPeriodsOnDay(com.orbit.solver.core.rule.RuleContext ctx, int day) {
        return ctx.maxPeriodsOn(day);
    }

    /** 造一个被固定为 value 的布尔常量（用于统一 OR/AND 编码，省去分支）。 */
    static BoolVar boolConst(CpModel model, String name, boolean value) {
        BoolVar v = model.newBoolVar(name);
        model.addEquality(v, value ? 1 : 0);
        return v;
    }

    /** 令 {@code target <=> OR(literals)}。 */
    static void encodeOr(CpModel model, BoolVar target, Literal[] literals) {
        model.addBoolOr(literals).onlyEnforceIf(target);
        model.addBoolAnd(negate(literals)).onlyEnforceIf(target.not());
    }

    /** 令 {@code target <=> AND(literals)}。 */
    static void encodeAnd(CpModel model, BoolVar target, Literal[] literals) {
        model.addBoolAnd(literals).onlyEnforceIf(target);
        model.addBoolOr(negate(literals)).onlyEnforceIf(target.not());
    }

    private static Literal[] negate(Literal[] literals) {
        Literal[] out = new Literal[literals.length];
        for (int i = 0; i < literals.length; i++) {
            out[i] = literals[i].not();
        }
        return out;
    }
}

