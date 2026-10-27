package com.orbit.solver.core.engine;

import com.google.ortools.sat.BoolVar;
import com.google.ortools.sat.CpModel;
import com.google.ortools.sat.IntVar;
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
 * CP-SAT 建模基础设施：建立决策变量与「成员布尔」，并维护各类索引。
 *
 * <p>这是 engine 层唯一负责变量构造的类，不含任何业务约束——约束全部在
 * {@code rule/handler} 里，通过 {@code compile.RuleContext} 使用这里的能力。
 *
 * <p><b>关键设计：成员布尔懒创建 + 缓存</b>。
 * {@code subjectAt(...)} 与 {@code teacherAt(...)} 第一次被请求时才建变量，
 * 之后复用同一个 {@link BoolVar}。原 Python 实现为「教师冲突」和「教师周上限」
 * 各建了一套完全相同的教师成员布尔（约占 28% 的变量），这里靠缓存自然消除重复。
 *
 * <p>为保持模型可复现，所有需要遍历的容器都保序（{@code LinkedHashMap} /
 * {@code TreeSet} / {@code ArrayList}），不使用 {@code HashMap} / {@code HashSet}。
 */
public final class SlotVariableIndex {

    static final int DAYS = 5;
    private static final String[] DAY_KEYS = {"Mon", "Tue", "Wed", "Thu", "Fri"};

    /** 固定课占用：该时段被哪个科目、哪位教师占住。 */
    public record FixedEntry(long subjectId, long teacherId) {
    }

    private record SlotKey(long classId, int day, int period) {
    }

    private record SubjectSlotKey(long classId, int day, int period, long subjectId) {
    }

    private record TeacherSlotKey(long teacherId, long classId, int day, int period) {
    }

    private final ScheduleInput in;
    private final CpModel model;

    private final Map<Long, ClassData> classData = new LinkedHashMap<>();
    private final List<ScheduleInput.Assignment> allAssigns = new ArrayList<>();
    private final Map<Long, List<Integer>> classAssigns = new LinkedHashMap<>();
    private final Map<Long, Map<Long, Integer>> classHours = new LinkedHashMap<>();
    private final Map<Long, Set<Long>> classSubjects = new LinkedHashMap<>();
    private final Map<SlotKey, FixedEntry> fixed = new LinkedHashMap<>();
    private final Map<Long, Set<Long>> teacherClasses = new LinkedHashMap<>();
    private final Map<Long, ScheduleInput.TeacherInfo> teachers = new LinkedHashMap<>();

    private final Map<SlotKey, IntVar> slotVars = new LinkedHashMap<>();
    private final Map<SubjectSlotKey, BoolVar> subjectSlot = new LinkedHashMap<>();
    private final Map<TeacherSlotKey, BoolVar> teacherSlot = new LinkedHashMap<>();

    private SlotVariableIndex(CpModel model, ScheduleInput in) {
        this.model = model;
        this.in = in;
        prepare();
    }

    /** 建立索引（不建变量）。变量在第一次被请求时才创建。 */
    public static SlotVariableIndex create(CpModel model, ScheduleInput in) {
        return new SlotVariableIndex(model, in);
    }

    private void prepare() {
        for (ScheduleInput.TeacherInfo t : in.teachers()) {
            teachers.put(t.id(), t);
        }

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
                periods[d] = (v == null) ? 6 : v;
                total += periods[d];
            }
            classData.put(c.id(), new ClassData(c.id(), periods, total));
        }

        for (ScheduleInput.Assignment a : in.assignments()) {
            int idx = allAssigns.size();
            allAssigns.add(a);
            classAssigns.computeIfAbsent(a.classId(), k -> new ArrayList<>()).add(idx);
        }

        for (ScheduleInput.GradeSubjectInfo gs : in.gradeSubjects()) {
            for (ScheduleInput.ClassInfo c : in.classes()) {
                if (c.gradeId() == gs.gradeId()) {
                    classHours.computeIfAbsent(c.id(), k -> new LinkedHashMap<>())
                            .put(gs.subjectId(), gs.weeklyHours());
                }
            }
        }

        for (Map.Entry<Long, ClassData> e : classData.entrySet()) {
            Set<Long> sids = new TreeSet<>();
            for (int gi : classAssigns.getOrDefault(e.getKey(), List.of())) {
                sids.add(allAssigns.get(gi).subjectId());
            }
            classSubjects.put(e.getKey(), sids);
        }

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

        for (ScheduleInput.Assignment a : in.assignments()) {
            teacherClasses.computeIfAbsent(a.teacherId(), k -> new LinkedHashSet<>()).add(a.classId());
        }
    }

    // ================================================================== 数据

    public CpModel model() {
        return model;
    }

    public List<ClassData> classDataInOrder() {
        return new ArrayList<>(classData.values());
    }

    public ClassData classData(long classId) {
        ClassData cd = classData.get(classId);
        if (cd == null) {
            throw new IllegalArgumentException("未知班级: " + classId);
        }
        return cd;
    }

    public Set<Long> allClassIds() {
        return java.util.Collections.unmodifiableSet(classData.keySet());
    }

    public List<Integer> assignIndicesOf(long classId) {
        return classAssigns.getOrDefault(classId, List.of());
    }

    public ScheduleInput.Assignment assignmentAt(int globalIndex) {
        return allAssigns.get(globalIndex);
    }

    /** 该班的「科目 -> 周课时」，保序。 */
    public Map<Long, Integer> weeklyHoursOf(long classId) {
        return classHours.getOrDefault(classId, Map.of());
    }

    public Set<Long> subjectsOf(long classId) {
        return classSubjects.getOrDefault(classId, Set.of());
    }

    public java.util.Collection<ScheduleInput.TeacherInfo> teachersInOrder() {
        return teachers.values();
    }

    public ScheduleInput.TeacherInfo teacher(long teacherId) {
        ScheduleInput.TeacherInfo t = teachers.get(teacherId);
        if (t == null) {
            throw new IllegalArgumentException("未知教师: " + teacherId);
        }
        return t;
    }

    public boolean teacherTeachesClass(long teacherId, long classId) {
        Set<Long> set = teacherClasses.get(teacherId);
        return set != null && set.contains(classId);
    }

    public int lastPeriod(long classId, int day) {
        return classData(classId).periods()[day];
    }

    // ================================================================== 固定课

    public boolean isFixed(long classId, int day, int period) {
        return fixed.containsKey(new SlotKey(classId, day, period));
    }

    public FixedEntry fixedAt(long classId, int day, int period) {
        return fixed.get(new SlotKey(classId, day, period));
    }

    // ================================================================== 变量

    /** 时段决策变量；固定课时段返回 null（那个时段不参与决策）。 */
    public IntVar slotVar(long classId, int day, int period) {
        SlotKey key = new SlotKey(classId, day, period);
        if (fixed.containsKey(key)) {
            return null;
        }
        IntVar cached = slotVars.get(key);
        if (cached != null) {
            return cached;
        }
        List<Integer> indices = assignIndicesOf(classId);
        if (indices.isEmpty()) {
            return null;
        }
        IntVar v = model.newIntVar(0, indices.size(),
                String.format("c%d_d%d_p%d", classId, day, period));
        slotVars.put(key, v);
        return v;
    }

    /** 该时段是否为某科目；固定课或该班无此科目时返回 null。 */
    public BoolVar subjectAt(long classId, int day, int period, long subjectId) {
        SubjectSlotKey key = new SubjectSlotKey(classId, day, period, subjectId);
        BoolVar cached = subjectSlot.get(key);
        if (cached != null) {
            return cached;
        }
        IntVar v = slotVar(classId, day, period);
        if (v == null) {
            return null;
        }
        List<Integer> indices = assignIndicesOf(classId);
        List<Integer> match = new ArrayList<>();
        for (int li = 0; li < indices.size(); li++) {
            if (assignmentAt(indices.get(li)).subjectId() == subjectId) {
                match.add(li);
            }
        }
        if (match.isEmpty()) {
            return null;
        }
        BoolVar b = membership(v, match,
                String.format("ss_%d_%d_%d_%d", classId, day, period, subjectId));
        subjectSlot.put(key, b);
        return b;
    }

    /** 该时段该教师是否在这个班上；固定课或无任教关系时返回 null。 */
    public BoolVar teacherAt(long teacherId, long classId, int day, int period) {
        TeacherSlotKey key = new TeacherSlotKey(teacherId, classId, day, period);
        BoolVar cached = teacherSlot.get(key);
        if (cached != null) {
            return cached;
        }
        IntVar v = slotVar(classId, day, period);
        if (v == null) {
            return null;
        }
        List<Integer> indices = assignIndicesOf(classId);
        List<Integer> match = new ArrayList<>();
        for (int li = 0; li < indices.size(); li++) {
            if (assignmentAt(indices.get(li)).teacherId() == teacherId) {
                match.add(li);
            }
        }
        if (match.isEmpty()) {
            return null;
        }
        BoolVar b = membership(v, match,
                String.format("tc_t%d_c%d_d%d_p%d", teacherId, classId, day, period));
        teacherSlot.put(key, b);
        return b;
    }

    /** 重化：b &lt;-&gt; (v ∈ matchIndices)。 */
    private BoolVar membership(IntVar v, List<Integer> matchIndices, String prefix) {
        BoolVar b = model.newBoolVar(prefix);
        List<Literal> conditions = new ArrayList<>(matchIndices.size());
        for (int li : matchIndices) {
            BoolVar eq = model.newBoolVar(prefix + "_eq" + li);
            model.addEquality(v, li).onlyEnforceIf(eq);
            model.addDifferent(v, li).onlyEnforceIf(eq.not());
            conditions.add(eq);
        }
        model.addBoolOr(conditions).onlyEnforceIf(b);
        List<Literal> negated = new ArrayList<>(conditions.size());
        for (Literal c : conditions) {
            negated.add(c.not());
        }
        model.addBoolAnd(negated).onlyEnforceIf(b.not());
        return b;
    }
}
