/*
 * ⚠ 仅为测试用的等价性基线（spike 产物），不属于生产代码。
 * 它是 backend/scheduler/engine.py 的逐行移植，刻意保留原实现的全部结构，
 * 包括那 28% 重复的教师成员布尔 —— 因为它的唯一用途是「与 Python 的模型逐字段对比」。
 * 生产实现见 compile/RuleCompiler + rule/handler。
 */package com.orbit.solver.core.legacy;

import com.google.ortools.sat.BoolVar;
import com.google.ortools.sat.CpModel;
import com.google.ortools.sat.IntVar;
import com.google.ortools.sat.LinearArgument;
import com.google.ortools.sat.LinearExpr;
import com.google.ortools.sat.LinearExprBuilder;
import com.google.ortools.sat.Literal;
import com.orbit.solver.core.model.ClassData;
import com.orbit.solver.core.model.ScheduleInput;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * {@code backend/scheduler/engine.py} 建模部分的<b>忠实移植</b>——一次性 spike。
 *
 * <p>唯一目的：证明 Java 版生成的 CP-SAT 模型与 Python 版逐字节等价
 * （{@code model.modelStats()} 里的 model_fingerprint 相同）。
 *
 * <p>为了这个目的，这里刻意做了三件「反直觉」的事，请勿"优化"：
 * <ol>
 *   <li><b>保留 Python 的重复变量</b>：原实现为教师冲突（tc_）和教师周上限（mh_）
 *       各建了一套完全相同的教师成员布尔，占约 28% 的变量。这里照抄。</li>
 *   <li><b>保留 Python 的遍历顺序</b>：fingerprint 是对序列化后的 proto 算的，
 *       包含变量与约束的顺序。因此所有需要遍历的容器都用 LinkedHashMap / TreeSet /
 *       ArrayList，<b>绝不用 HashMap / HashSet</b>。</li>
 *   <li><b>保留 Python 的变量命名</b>：用 String.format 精确复刻，一个字都不能差。</li>
 * </ol>
 *
 * <p>下一阶段会把它重构成 RuleContext + RuleHandler（去掉重复变量、收敛固定课处理），
 * 那份才是长期维护的代码。所以不要在这个文件上做抽象。
 */
public final class FaithfulModelBuilder {

    static final int DAYS = 5;
    private static final String[] DAY_KEYS = {"Mon", "Tue", "Wed", "Thu", "Fri"};

    /** 对应 Python 里那些提前 {@code return []} 的构造性不可行。 */
    public static final class ConstructionInfeasible extends RuntimeException {
        public ConstructionInfeasible(String message) {
            super(message);
        }
    }

    private record SlotKey(long classId, int day, int period) {
    }

    private record SubjectSlotKey(long classId, int day, int period, long subjectId) {
    }

    /** 固定课：该时段被哪个科目、哪位教师占用。 */
    public record FixedEntry(long subjectId, long teacherId) {
    }

    private final ScheduleInput in;
    private final CpModel model = new CpModel();

    // ---- 中间索引（全部保序） ----
    private final Map<Long, ClassData> classData = new LinkedHashMap<>();
    private final List<ScheduleInput.Assignment> allAssigns = new ArrayList<>();
    private final Map<Long, List<Integer>> classAssigns = new LinkedHashMap<>();
    private final Map<Long, Map<Long, Integer>> classHours = new LinkedHashMap<>();
    private final Map<Long, Set<Long>> classSubjects = new LinkedHashMap<>();
    private final Map<SlotKey, FixedEntry> fixed = new LinkedHashMap<>();
    private final Map<Long, Set<Long>> teacherClasses = new LinkedHashMap<>();
    private final Map<Long, ScheduleInput.TeacherInfo> teachers = new LinkedHashMap<>();

    // ---- 决策变量 ----
    private final Map<SlotKey, IntVar> slotVars = new LinkedHashMap<>();
    private final Map<SubjectSlotKey, BoolVar> subjectSlot = new LinkedHashMap<>();

    /** 目标函数里的常数项（Python 里 {@code penalty_terms.append(1)} 的情形）。 */
    private int penaltyConstant = 0;

    public FaithfulModelBuilder(ScheduleInput in) {
        this.in = in;
    }

    /** 建模完成后的 CP-SAT 模型（供打印 modelStats / 指纹比对）。 */
    public CpModel model() {
        return model;
    }

    public CpModel build() {
        prepare();
        createSlotVars();
        createSubjectSlotBooleans();
        hardSubjectHours();
        hardTeacherNoConflict();
        hardTeacherWeeklyLimit();
        softSameSubjectPerDay();
        softNoConsecutiveSameSubject();
        commitObjective();
        return model;
    }

    // ==================================================================
    // 数据准备
    // ==================================================================

    private void prepare() {
        for (ScheduleInput.TeacherInfo t : in.teachers()) {
            teachers.put(t.id(), t);
        }

        // class_data：跳过找不到年级的班级（对应 Python 的 continue）
        Map<Long, ScheduleInput.GradeInfo> gradeById = new LinkedHashMap<>();
        for (ScheduleInput.GradeInfo g : in.grades()) {
            gradeById.put(g.id(), g);
        }
        for (ScheduleInput.ClassInfo c : in.classes()) {
            ScheduleInput.GradeInfo g = gradeById.get(c.gradeId());
            if (g == null) {
                continue;
            }
            int[] periods = new int[DAYS];
            int total = 0;
            for (int d = 0; d < DAYS; d++) {
                Integer v = g.periodsPerDay().get(DAY_KEYS[d]);
                periods[d] = (v == null) ? 6 : v;      // Python: ppd.get(d, 6)
                total += periods[d];
            }
            classData.put(c.id(), new ClassData(c.id(), periods, total));
        }

        // all_assigns / class_assigns
        for (ScheduleInput.Assignment a : in.assignments()) {
            int idx = allAssigns.size();
            allAssigns.add(a);
            classAssigns.computeIfAbsent(a.classId(), k -> new ArrayList<>()).add(idx);
        }

        // class_hours：外层 gradeSubjects、内层 classes —— 顺序决定 day_exc 的创建次序
        for (ScheduleInput.GradeSubjectInfo gs : in.gradeSubjects()) {
            for (ScheduleInput.ClassInfo c : in.classes()) {
                if (c.gradeId() == gs.gradeId()) {
                    classHours.computeIfAbsent(c.id(), k -> new LinkedHashMap<>())
                            .put(gs.subjectId(), gs.weeklyHours());
                }
            }
        }

        // class_subjects：Python 是 set，小整数集合的迭代实际按升序，故用 TreeSet 对齐
        for (Map.Entry<Long, ClassData> e : classData.entrySet()) {
            Set<Long> sids = new TreeSet<>();
            for (int gi : classAssigns.getOrDefault(e.getKey(), List.of())) {
                sids.add(allAssigns.get(gi).subjectId());
            }
            classSubjects.put(e.getKey(), sids);
        }

        // fixed：年级级固定课展开到该年级每个班（要求该班该科恰好一条任教关系）
        for (ScheduleInput.FixedSlotRule rule : in.gradeFixedSlots()) {
            for (ScheduleInput.ClassInfo c : in.classes()) {
                if (c.gradeId() != rule.gradeId()) {
                    continue;
                }
                List<ScheduleInput.Assignment> matches = new ArrayList<>();
                for (ScheduleInput.Assignment a : in.assignments()) {
                    if (a.classId() == c.id() && a.subjectId() == rule.subjectId()) {
                        matches.add(a);
                    }
                }
                if (matches.size() != 1) {
                    throw new ConstructionInfeasible(
                            "固定课展开失败：班级 " + c.id() + " 科目 " + rule.subjectId()
                                    + " 的任教关系不是恰好一条（实际 " + matches.size() + "）");
                }
                fixed.put(new SlotKey(c.id(), rule.day(), rule.period()),
                        new FixedEntry(rule.subjectId(), matches.get(0).teacherId()));
            }
        }

        // teacher_classes
        for (ScheduleInput.Assignment a : in.assignments()) {
            teacherClasses.computeIfAbsent(a.teacherId(), k -> new LinkedHashSet<>()).add(a.classId());
        }
    }

    // ==================================================================
    // 变量
    // ==================================================================

    /** 每个「班级 × 天 × 节」一个整数变量：取值 0..n-1 表示第几条任教关系，n 表示空堂。 */
    private void createSlotVars() {
        for (Map.Entry<Long, ClassData> e : classData.entrySet()) {
            long cid = e.getKey();
            ClassData cd = e.getValue();
            List<Integer> indices = classAssigns.getOrDefault(cid, List.of());
            if (indices.isEmpty()) {
                continue;
            }
            for (int day = 0; day < DAYS; day++) {
                for (int period = 0; period < cd.periods()[day]; period++) {
                    if (fixed.containsKey(new SlotKey(cid, day, period))) {
                        continue;
                    }
                    slotVars.put(new SlotKey(cid, day, period),
                            model.newIntVar(0, indices.size(),
                                    String.format("c%d_d%d_p%d", cid, day, period)));
                }
            }
        }
    }

    /** 派生布尔：该时段上的科目是否等于某科目。 */
    private void createSubjectSlotBooleans() {
        for (Map.Entry<Long, ClassData> e : classData.entrySet()) {
            long cid = e.getKey();
            ClassData cd = e.getValue();
            List<Integer> indices = classAssigns.getOrDefault(cid, List.of());
            if (indices.isEmpty()) {
                continue;
            }
            for (int day = 0; day < DAYS; day++) {
                for (int period = 0; period < cd.periods()[day]; period++) {
                    if (fixed.containsKey(new SlotKey(cid, day, period))) {
                        continue;
                    }
                    IntVar v = slotVars.get(new SlotKey(cid, day, period));
                    if (v == null) {
                        continue;
                    }
                    for (long sid : classSubjects.getOrDefault(cid, Set.of())) {
                        List<Integer> match = new ArrayList<>();
                        for (int li = 0; li < indices.size(); li++) {
                            if (allAssigns.get(indices.get(li)).subjectId() == sid) {
                                match.add(li);
                            }
                        }
                        if (match.isEmpty()) {
                            continue;
                        }
                        String prefix = String.format("ss_%d_%d_%d_%d", cid, day, period, sid);
                        subjectSlot.put(new SubjectSlotKey(cid, day, period, sid),
                                membership(v, match, prefix));
                    }
                }
            }
        }
    }

    /**
     * 重化：构造布尔 b，使 {@code b <-> (v ∈ matchIndices)}。
     *
     * <p>创建顺序必须与 Python 一致：先建 b，再逐个建 eq 变量并挂两条等价约束，
     * 最后才是 bool_or / bool_and。
     */
    private BoolVar membership(IntVar v, List<Integer> matchIndices, String prefix) {
        // Python: b = model.NewBoolVar(f"ss_...")
        BoolVar b = model.newBoolVar(prefix);
        List<Literal> conditions = new ArrayList<>(matchIndices.size());
        for (int li : matchIndices) {
            BoolVar eq = model.newBoolVar(prefix + "_eq" + li);
            model.addEquality(v, li).onlyEnforceIf(eq);
            model.addDifferent(v, li).onlyEnforceIf(eq.not());
            conditions.add(eq);
        }
        // Python: AddBoolOr(conditions).OnlyEnforceIf(b)
        model.addBoolOr(conditions).onlyEnforceIf(b);
        // Python: AddBoolAnd([c.Not() for c in conditions]).OnlyEnforceIf(b.Not())
        List<Literal> negated = new ArrayList<>(conditions.size());
        for (Literal c : conditions) {
            negated.add(c.not());
        }
        model.addBoolAnd(negated).onlyEnforceIf(b.not());
        return b;
    }

    // ==================================================================
    // 硬约束
    // ==================================================================

    /** 硬约束 1：每科周课时数必须排满（固定课已占的节数要扣掉）。 */
    private void hardSubjectHours() {
        for (Map.Entry<Long, ClassData> e : classData.entrySet()) {
            long cid = e.getKey();
            ClassData cd = e.getValue();
            List<Integer> indices = classAssigns.getOrDefault(cid, List.of());
            if (indices.isEmpty()) {
                continue;
            }
            for (Map.Entry<Long, Integer> h : classHours.getOrDefault(cid, Map.of()).entrySet()) {
                long sid = h.getKey();
                int needed = h.getValue();

                int fixedCount = 0;
                for (int day = 0; day < DAYS; day++) {
                    for (int period = 0; period < cd.periods()[day]; period++) {
                        FixedEntry fe = fixed.get(new SlotKey(cid, day, period));
                        if (fe != null && fe.subjectId() == sid) {
                            fixedCount++;
                        }
                    }
                }
                int remaining = needed - fixedCount;
                if (remaining <= 0) {
                    continue;
                }

                List<BoolVar> bools = new ArrayList<>();
                for (int day = 0; day < DAYS; day++) {
                    for (int period = 0; period < cd.periods()[day]; period++) {
                        BoolVar b = subjectSlot.get(new SubjectSlotKey(cid, day, period, sid));
                        if (b != null) {
                            bools.add(b);
                        }
                    }
                }
                if (!bools.isEmpty()) {
                    model.addEquality(LinearExpr.sum(bools.toArray(new LinearArgument[0])), remaining);
                }
            }
        }
    }

    /** 硬约束 2：同一教师同一天同一节最多一节课。 */
    private void hardTeacherNoConflict() {
        int maxPeriods = 0;
        for (ClassData cd : classData.values()) {
            for (int p : cd.periods()) {
                maxPeriods = Math.max(maxPeriods, p);
            }
        }
        for (long tid : teachers.keySet()) {
            for (int day = 0; day < DAYS; day++) {
                for (int period = 0; period < maxPeriods; period++) {
                    List<BoolVar> bools = new ArrayList<>();
                    int fixedCount = 0;
                    for (Map.Entry<Long, ClassData> e : classData.entrySet()) {
                        long cid = e.getKey();
                        ClassData cd = e.getValue();
                        if (period >= cd.periods()[day]) {
                            continue;
                        }
                        Set<Long> tcs = teacherClasses.get(tid);
                        if (tcs == null || !tcs.contains(cid)) {
                            continue;
                        }
                        FixedEntry fe = fixed.get(new SlotKey(cid, day, period));
                        if (fe != null) {
                            if (fe.teacherId() == tid) {
                                fixedCount++;
                            }
                            continue;
                        }
                        IntVar v = slotVars.get(new SlotKey(cid, day, period));
                        if (v == null) {
                            continue;
                        }
                        List<Integer> indices = classAssigns.getOrDefault(cid, List.of());
                        List<Integer> match = new ArrayList<>();
                        for (int li = 0; li < indices.size(); li++) {
                            if (allAssigns.get(indices.get(li)).teacherId() == tid) {
                                match.add(li);
                            }
                        }
                        if (match.isEmpty()) {
                            continue;
                        }
                        String prefix = String.format("tc_t%d_c%d_d%d_p%d", tid, cid, day, period);
                        bools.add(membership(v, match, prefix));
                    }
                    if (fixedCount > 1) {
                        throw new ConstructionInfeasible(
                                "教师 " + tid + " 在第 " + day + " 天第 " + period
                                        + " 节被固定课占了 " + fixedCount + " 次");
                    }
                    if (!bools.isEmpty()) {
                        model.addLessOrEqual(LinearExpr.newBuilder()
                                .addSum(bools.toArray(new LinearArgument[0]))
                                .add(fixedCount).build(), 1);
                    }
                }
            }
        }
    }

    /** 硬约束 3：教师每周课时不超过上限。 */
    private void hardTeacherWeeklyLimit() {
        for (Map.Entry<Long, ScheduleInput.TeacherInfo> te : teachers.entrySet()) {
            long tid = te.getKey();
            int limit = te.getValue().maxWeeklyHours();
            List<BoolVar> bools = new ArrayList<>();
            int fixedCount = 0;
            for (Map.Entry<Long, ClassData> e : classData.entrySet()) {
                long cid = e.getKey();
                ClassData cd = e.getValue();
                Set<Long> tcs = teacherClasses.get(tid);
                if (tcs == null || !tcs.contains(cid)) {
                    continue;
                }
                List<Integer> indices = classAssigns.getOrDefault(cid, List.of());
                if (indices.isEmpty()) {
                    continue;
                }
                for (int day = 0; day < DAYS; day++) {
                    for (int period = 0; period < cd.periods()[day]; period++) {
                        FixedEntry fe = fixed.get(new SlotKey(cid, day, period));
                        if (fe != null) {
                            if (fe.teacherId() == tid) {
                                fixedCount++;
                            }
                            continue;
                        }
                        IntVar v = slotVars.get(new SlotKey(cid, day, period));
                        if (v == null) {
                            continue;
                        }
                        List<Integer> match = new ArrayList<>();
                        for (int li = 0; li < indices.size(); li++) {
                            if (allAssigns.get(indices.get(li)).teacherId() == tid) {
                                match.add(li);
                            }
                        }
                        if (match.isEmpty()) {
                            continue;
                        }
                        String prefix = String.format("mh_t%d_c%d_d%d_p%d", tid, cid, day, period);
                        bools.add(membership(v, match, prefix));
                    }
                }
            }
            if (!bools.isEmpty()) {
                model.addLessOrEqual(LinearExpr.newBuilder()
                        .addSum(bools.toArray(new LinearArgument[0]))
                        .add(fixedCount).build(), limit);
            }
        }
    }

    // ==================================================================
    // 软约束 + 目标
    // ==================================================================

    /** 软约束 1：同一天同一科目超过 1 节的部分罚 1（单向下界线性化）。 */
    private void softSameSubjectPerDay() {
        List<LinearArgument> penalty = new ArrayList<>();
        for (Map.Entry<Long, ClassData> e : classData.entrySet()) {
            long cid = e.getKey();
            ClassData cd = e.getValue();
            for (long sid : classSubjects.getOrDefault(cid, Set.of())) {
                for (int day = 0; day < DAYS; day++) {
                    List<BoolVar> dayBools = new ArrayList<>();
                    int fixedCount = 0;
                    for (int period = 0; period < cd.periods()[day]; period++) {
                        FixedEntry fe = fixed.get(new SlotKey(cid, day, period));
                        if (fe != null) {
                            if (fe.subjectId() == sid) {
                                fixedCount++;
                            }
                            continue;
                        }
                        BoolVar b = subjectSlot.get(new SubjectSlotKey(cid, day, period, sid));
                        if (b != null) {
                            dayBools.add(b);
                        }
                    }
                    int totalPossible = fixedCount + dayBools.size();
                    if (totalPossible <= 1) {
                        continue;
                    }
                    // excess >= sum(day_bools) + fixed_cnt - 1，最小化时自动取到 max(0, ...)
                    IntVar excess = model.newIntVar(0, totalPossible,
                            String.format("day_exc_%d_%d_%d", cid, sid, day));
                    model.addGreaterOrEqual(excess, LinearExpr.newBuilder()
                            .addSum(dayBools.toArray(new LinearArgument[0]))
                            .add(fixedCount - 1).build());
                    penalty.add(excess);
                }
            }
        }
        allPenalties.addAll(penalty);
    }

    /** 软约束 2：同一科目连排两节罚 1。 */
    private void softNoConsecutiveSameSubject() {
        List<LinearArgument> penalty = new ArrayList<>();
        for (Map.Entry<Long, ClassData> e : classData.entrySet()) {
            long cid = e.getKey();
            ClassData cd = e.getValue();
            for (int day = 0; day < DAYS; day++) {
                int maxPeriods = cd.periods()[day];
                for (int period = 0; period < maxPeriods - 1; period++) {
                    FixedEntry f1 = fixed.get(new SlotKey(cid, day, period));
                    FixedEntry f2 = fixed.get(new SlotKey(cid, day, period + 1));
                    for (long sid : classSubjects.getOrDefault(cid, Set.of())) {
                        if (f1 != null && f2 != null) {
                            if (f1.subjectId() == sid && f2.subjectId() == sid) {
                                penaltyConstant++;      // Python: penalty_terms.append(1)
                            }
                            continue;
                        }
                        if (f1 != null) {
                            if (f1.subjectId() != sid) {
                                continue;
                            }
                            BoolVar b2 = subjectSlot.get(new SubjectSlotKey(cid, day, period + 1, sid));
                            if (b2 != null) {
                                penalty.add(b2);
                            }
                        } else if (f2 != null) {
                            if (f2.subjectId() != sid) {
                                continue;
                            }
                            BoolVar b1 = subjectSlot.get(new SubjectSlotKey(cid, day, period, sid));
                            if (b1 != null) {
                                penalty.add(b1);
                            }
                        } else {
                            BoolVar b1 = subjectSlot.get(new SubjectSlotKey(cid, day, period, sid));
                            BoolVar b2 = subjectSlot.get(new SubjectSlotKey(cid, day, period + 1, sid));
                            if (b1 != null && b2 != null) {
                                BoolVar both = model.newBoolVar(
                                        String.format("cons_%d_%d_%d_%d", cid, day, period, sid));
                                model.addBoolAnd(new Literal[] {b1, b2}).onlyEnforceIf(both);
                                model.addBoolOr(new Literal[] {b1.not(), b2.not()})
                                        .onlyEnforceIf(both.not());
                                penalty.add(both);
                            }
                        }
                    }
                }
            }
        }
        allPenalties.addAll(penalty);
    }

    private final List<LinearArgument> allPenalties = new ArrayList<>();

    /** 全部约束加完后调用，对应 Python 的 {@code if penalty_terms: model.Minimize(sum(penalty_terms))}。 */
    private void commitObjective() {
        if (allPenalties.isEmpty() && penaltyConstant == 0) {
            return;
        }
        LinearExprBuilder builder = LinearExpr.newBuilder();
        if (!allPenalties.isEmpty()) {
            builder.addSum(allPenalties.toArray(new LinearArgument[0]));
        }
        if (penaltyConstant != 0) {
            builder.add(penaltyConstant);
        }
        model.minimize(builder.build());
    }

    // ==================================================================
    // 供结果提取使用的访问器
    // ==================================================================

    public List<ClassData> classDataInOrder() {
        return new ArrayList<>(classData.values());
    }

    public List<Integer> assignIndicesOf(long classId) {
        return classAssigns.getOrDefault(classId, List.of());
    }

    public ScheduleInput.Assignment assignmentAt(int globalIndex) {
        return allAssigns.get(globalIndex);
    }

    public boolean isFixed(long classId, int day, int period) {
        return fixed.containsKey(new SlotKey(classId, day, period));
    }

    public FixedEntry fixedAt(long classId, int day, int period) {
        return fixed.get(new SlotKey(classId, day, period));
    }

    public IntVar slotVar(long classId, int day, int period) {
        return slotVars.get(new SlotKey(classId, day, period));
    }
}
