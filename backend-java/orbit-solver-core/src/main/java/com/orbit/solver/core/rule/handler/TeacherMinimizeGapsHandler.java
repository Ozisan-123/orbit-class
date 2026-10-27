package com.orbit.solver.core.rule.handler;

import com.google.ortools.sat.BoolVar;
import com.google.ortools.sat.CpModel;
import com.google.ortools.sat.LinearExpr;
import com.google.ortools.sat.Literal;
import com.orbit.solver.core.model.ScheduleInput;
import com.orbit.solver.core.rule.PenaltyCollector;
import com.orbit.solver.core.rule.RuleContext;
import com.orbit.solver.core.rule.RuleHandler;
import com.orbit.solver.core.rule.RuleParamSpec;
import com.orbit.solver.core.rule.RuleParams;

import java.util.ArrayList;
import java.util.List;

/**
 * 可配置规则：教师课表尽量不留空档。
 *
 * <p>{@code teacher_id} 可省略 —— 省略时表示所有教师。
 *
 * <h2>为什么这条最麻烦</h2>
 * 前面几条都只是「把某个变量加起来，和常数比一比」。空档不一样：它是一个
 * <b>「量的形状」</b>上的性质 —— 要知道某节课是不是夹在「这天第一节课」和
 * 「这天最后一节课」之间。
 *
 * <h2>建模：空洞计数</h2>
 * 常见写法是先求 first / last 再算 {@code (last-first+1) - 课时数}，但那要引入
 * 最小/最大等式和哨兵，还得单独处理「当天没课」（否则会算出负数）。
 *
 * <p>这里用等价的<b>逐节判定</b>写法，O(n) 个辅助变量且不需要哨兵：
 * <pre>
 *   busy[p]   : 该教师这天第 p 节有课
 *   before[p] : 第 p 节之前有课     = OR(busy[0..p-1])      （递推：before[p] = before[p-1] OR busy[p-1]）
 *   after[p]  : 第 p 节之后有课     = OR(busy[p+1..n-1])    （递推，从后往前）
 *   hole[p]   : before[p] AND after[p] AND NOT busy[p]
 *   空档数     = Σ hole[p]
 * </pre>
 *
 * <p>它天然处理两个边界，不需要额外分支：
 * <ul>
 *   <li><b>当天没课</b>：所有 busy 为 0 → 所有 before 为 0 → 没有 hole → 空档数 0 ✅
 *       （这正是 first/last 写法容易算出负数的那个坑）</li>
 *   <li><b>各年级节数不同</b>：某节之后没课了，after 自然为 0，不会被误判成空档 ✅</li>
 * </ul>
 *
 * <p>硬软两用：硬 → 空档数必须为 0；软 → 每个空档计 1 分惩罚（更常见的用法）。
 *
 * <p><b>成本提示</b>：每个「教师 × 天」约 4n 个辅助变量。对 36 教师 × 5 天 × 8 节
 * 大约是 5,700 个变量，是这些规则里最贵的一条，因此只在规则集里显式配置时才生效。
 * 另外用了「当天最多只能上 1 节就不可能产生空档」做短路，能省掉大部分教师。
 */
public final class TeacherMinimizeGapsHandler implements RuleHandler {

    public static final String TYPE = "teacher_minimize_gaps";
    public static final String P_TEACHER = "teacher_id";

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public String displayName() {
        return "教师课表不留空档";
    }

    @Override
    public List<RuleParamSpec> params() {
        return List.of(
                RuleParamSpec.optionalInt(P_TEACHER, "教师 ID（留空表示所有教师）",
                        1, Integer.MAX_VALUE));
    }

    @Override
    public String describe(RuleParams params) {
        return params.has(P_TEACHER)
                ? "教师 " + params.longOr(P_TEACHER, -1) + " 的课表尽量不留空档"
                : "所有教师的课表尽量不留空档";
    }

    @Override
    public void apply(RuleContext ctx, RuleParams params, boolean hard, PenaltyCollector penalties) {
        CpModel model = ctx.model();
        Long only = params.has(P_TEACHER) ? params.requireLong(P_TEACHER) : null;

        for (ScheduleInput.TeacherInfo teacher : ctx.teachers()) {
            long teacherId = teacher.id();
            if (only != null && only != teacherId) {
                continue;
            }
            for (int day = 0; day < ctx.days(); day++) {
                int maximumLessons = ctx.fixedCountForTeacherOnDay(teacherId, day)
                        + ctx.freeTeacherSlotsOnDay(teacherId, day).size();
                if (maximumLessons <= 1) {
                    continue;       // 一天最多一节，不可能出现空档
                }
                applyForTeacherDay(ctx, model, penalties, teacherId, day, hard);
            }
        }
    }

    private void applyForTeacherDay(RuleContext ctx, CpModel model, PenaltyCollector penalties,
                                    long teacherId, int day, boolean hard) {
        int n = ctx.maxPeriodsOn(day);
        if (n <= 1) {
            return;
        }
        String tag = String.format("tg_%d_%d", teacherId, day);

        // busy[p] <=> 该教师这天第 p 节有课
        BoolVar[] busy = new BoolVar[n];
        for (int p = 0; p < n; p++) {
            busy[p] = model.newBoolVar(tag + "_busy_" + p);
            int fixed = ctx.fixedCountForTeacher(teacherId, day, p);
            List<BoolVar> vars = ctx.freeTeacherSlots(teacherId, day, p);
            if (fixed > 0) {
                model.addEquality(busy[p], 1);
            } else if (vars.isEmpty()) {
                model.addEquality(busy[p], 0);
            } else {
                HandlerSupport.encodeOr(model, busy[p], HandlerSupport.literals(vars));
            }
        }

        // before[p] = 第 p 节之前有课
        BoolVar[] before = new BoolVar[n];
        before[0] = HandlerSupport.boolConst(model, tag + "_bf_0", false);
        for (int p = 1; p < n; p++) {
            before[p] = model.newBoolVar(tag + "_bf_" + p);
            HandlerSupport.encodeOr(model, before[p],
                    new Literal[] {before[p - 1], busy[p - 1]});
        }

        // after[p] = 第 p 节之后有课
        BoolVar[] after = new BoolVar[n];
        after[n - 1] = HandlerSupport.boolConst(model, tag + "_af_" + (n - 1), false);
        for (int p = n - 2; p >= 0; p--) {
            after[p] = model.newBoolVar(tag + "_af_" + p);
            HandlerSupport.encodeOr(model, after[p],
                    new Literal[] {after[p + 1], busy[p + 1]});
        }

        // hole[p] = before[p] AND after[p] AND NOT busy[p]
        List<BoolVar> holes = new ArrayList<>(n);
        for (int p = 0; p < n; p++) {
            BoolVar hole = model.newBoolVar(tag + "_hole_" + p);
            HandlerSupport.encodeAnd(model, hole,
                    new Literal[] {before[p], after[p], busy[p].not()});
            holes.add(hole);
        }

        if (hard) {
            model.addEquality(LinearExpr.sum(HandlerSupport.args(holes)), 0);
        } else {
            for (BoolVar hole : holes) {
                penalties.add(hole);
            }
        }
    }
}
