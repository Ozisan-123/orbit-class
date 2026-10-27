package com.orbit.solver.core.rule;

import com.google.ortools.sat.BoolVar;
import com.google.ortools.sat.CpModel;
import com.google.ortools.sat.IntVar;
import com.orbit.solver.core.engine.SlotVariableIndex;
import com.orbit.solver.core.model.ClassData;
import com.orbit.solver.core.model.ScheduleInput;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 规则作者看到的「课表世界」。
 *
 * <p><b>这个类的存在理由，是把固定课处理收敛到一处。</b>
 * 原 Python 实现里 {@code fixed} 相关处理散落 39 处：每一条约束都要自己判断
 * 「这个时段是不是固定课」、自己把固定课的数量数出来、再手工加进线性表达式。
 * 任何新规则（人写或 AI 写）都必须重复这套记账，因此**极易漏**。
 *
 * <p>规则作者现在只需要问业务问题：
 * <pre>{@code
 * int fixed = ctx.fixedCountForTeacher(teacherId, day, period);   // 固定课已算在内
 * List<BoolVar> vars = ctx.freeTeacherSlots(teacherId, day, period); // 只含非固定时段
 * model.addLessOrEqual(sum(vars) + fixed, 1);
 * }</pre>
 *
 * <p>于是 {@code fsid} / {@code ftid} / {@code fixed_count} 这些记账细节不再出现在任何 handler 里。
 */
public final class RuleContext {

    public static final int DAYS = 5;

    private final SlotVariableIndex index;

    public RuleContext(SlotVariableIndex index) {
        this.index = index;
    }

    public CpModel model() {
        return index.model();
    }

    public int days() {
        return DAYS;
    }

    // ------------------------------------------------------------ 基本数据

    public List<ClassData> classes() {
        return index.classDataInOrder();
    }

    public Collection<Long> classIds() {
        return index.allClassIds();
    }

    public ClassData classData(long classId) {
        return index.classData(classId);
    }

    public Set<Long> subjectsOf(long classId) {
        return index.subjectsOf(classId);
    }

    /** 该班「科目 -> 周课时」，保序。 */
    public Map<Long, Integer> weeklyHoursOf(long classId) {
        return index.weeklyHoursOf(classId);
    }

    public Collection<ScheduleInput.TeacherInfo> teachers() {
        return index.teachersInOrder();
    }

    /** 该教师 ID 是否存在（用于把「引用了不存在的教师」变成配置错误而非无解）。 */
    public boolean teacherExists(long teacherId) {
        for (ScheduleInput.TeacherInfo t : teachers()) {
            if (t.id() == teacherId) {
                return true;
            }
        }
        return false;
    }

    public int teacherWeeklyLimit(long teacherId) {
        return index.teacher(teacherId).maxWeeklyHours();
    }

    public boolean teacherTeachesClass(long teacherId, long classId) {
        return index.teacherTeachesClass(teacherId, classId);
    }

    /** 该班该天的节数（不同年级可以不同，所以「最后一节」必须按班按天算）。 */
    public int periodsOn(long classId, int day) {
        return index.lastPeriod(classId, day);
    }

    // ------------------------------------------------------------ 固定课计数

    public boolean isFixed(long classId, int day, int period) {
        return index.isFixed(classId, day, period);
    }

    /** 该时段固定课的科目；非固定课返回 -1。 */
    public long fixedSubjectAt(long classId, int day, int period) {
        var fe = index.fixedAt(classId, day, period);
        return fe == null ? -1 : fe.subjectId();
    }

    /** 该时段固定课的教师；非固定课返回 -1。 */
    public long fixedTeacherAt(long classId, int day, int period) {
        var fe = index.fixedAt(classId, day, period);
        return fe == null ? -1 : fe.teacherId();
    }

    /** 该班该科目整周被固定课占掉的节数。 */
    public int fixedCountForSubject(long classId, long subjectId) {
        int count = 0;
        int periods = periodsOn(classId, 0);
        for (int day = 0; day < DAYS; day++) {
            count += fixedCountForSubjectOnDay(classId, subjectId, day);
        }
        return count;
    }

    public int fixedCountForSubjectOnDay(long classId, long subjectId, int day) {
        int count = 0;
        for (int period = 0; period < periodsOn(classId, day); period++) {
            if (fixedSubjectAt(classId, day, period) == subjectId) {
                count++;
            }
        }
        return count;
    }

    /** 该教师整周被固定课占掉的节数。 */
    public int fixedCountForTeacher(long teacherId) {
        int count = 0;
        for (long classId : classIds()) {
            if (!teacherTeachesClass(teacherId, classId)) {
                continue;
            }
            for (int day = 0; day < DAYS; day++) {
                for (int period = 0; period < periodsOn(classId, day); period++) {
                    if (fixedTeacherAt(classId, day, period) == teacherId) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    /** 该教师在某天某节被固定课占掉几次（&gt;1 表示固定课本身就撞车）。 */
    public int fixedCountForTeacher(long teacherId, int day, int period) {
        int count = 0;
        for (long classId : classIds()) {
            if (!teacherTeachesClass(teacherId, classId)) {
                continue;
            }
            if (period >= periodsOn(classId, day)) {
                continue;
            }
            if (fixedTeacherAt(classId, day, period) == teacherId) {
                count++;
            }
        }
        return count;
    }

    /** 该教师某天被固定课占掉的节数。 */
    public int fixedCountForTeacherOnDay(long teacherId, int day) {
        int count = 0;
        for (int period = 0; period < maxPeriodsOn(day); period++) {
            count += fixedCountForTeacher(teacherId, day, period);
        }
        return count;
    }

    // ------------------------------------------------------------ 变量访问

    /** 时段决策变量；固定课或空班返回 null。 */
    public IntVar slotVar(long classId, int day, int period) {
        return index.slotVar(classId, day, period);
    }

    /** 「该时段是某科目」的布尔；固定课或无此科目返回 null。 */
    public BoolVar subjectAt(long classId, int day, int period, long subjectId) {
        return index.subjectAt(classId, day, period, subjectId);
    }

    /** 「该教师在该班上这一节」的布尔；固定课或无任教关系返回 null。 */
    public BoolVar teacherAt(long teacherId, long classId, int day, int period) {
        return index.teacherAt(teacherId, classId, day, period);
    }

    /** 该班该科目整周所有「非固定课」时段的布尔（懒创建，重复调用返回同一批变量）。 */
    public List<BoolVar> freeSubjectSlots(long classId, long subjectId) {
        List<BoolVar> out = new ArrayList<>();
        for (int day = 0; day < DAYS; day++) {
            for (int period = 0; period < periodsOn(classId, day); period++) {
                BoolVar b = subjectAt(classId, day, period, subjectId);
                if (b != null) {
                    out.add(b);
                }
            }
        }
        return out;
    }

    public List<BoolVar> freeSubjectSlotsOnDay(long classId, long subjectId, int day) {
        List<BoolVar> out = new ArrayList<>();
        for (int period = 0; period < periodsOn(classId, day); period++) {
            BoolVar b = subjectAt(classId, day, period, subjectId);
            if (b != null) {
                out.add(b);
            }
        }
        return out;
    }

    /**
     * 该教师整周所有「非固定课」时段的布尔。
     * 与 {@link #freeTeacherSlots(long, int, int)} 返回的是**同一批缓存变量**，
     * 因此「教师不冲突」与「教师周上限」两条规则不会各建一套（原实现重复了约 28% 的变量）。
     */
    public List<BoolVar> freeTeacherSlots(long teacherId) {
        List<BoolVar> out = new ArrayList<>();
        for (long classId : classIds()) {
            if (!teacherTeachesClass(teacherId, classId)) {
                continue;
            }
            for (int day = 0; day < DAYS; day++) {
                for (int period = 0; period < periodsOn(classId, day); period++) {
                    BoolVar b = teacherAt(teacherId, classId, day, period);
                    if (b != null) {
                        out.add(b);
                    }
                }
            }
        }
        return out;
    }

    /** 该教师在某天某节、跨各班的布尔。 */
    public List<BoolVar> freeTeacherSlots(long teacherId, int day, int period) {
        List<BoolVar> out = new ArrayList<>();
        for (long classId : classIds()) {
            if (!teacherTeachesClass(teacherId, classId)) {
                continue;
            }
            if (period >= periodsOn(classId, day)) {
                continue;
            }
            BoolVar b = teacherAt(teacherId, classId, day, period);
            if (b != null) {
                out.add(b);
            }
        }
        return out;
    }

    /** 该教师某天所有「非固定课」时段的布尔（跨各班）。 */
    public List<BoolVar> freeTeacherSlotsOnDay(long teacherId, int day) {
        List<BoolVar> out = new ArrayList<>();
        for (int period = 0; period < maxPeriodsOn(day); period++) {
            out.addAll(freeTeacherSlots(teacherId, day, period));
        }
        return out;
    }

    /** 某一天里所有班级中最大的节数（各班节数可以不同，遍历「天 × 节」时用它）。 */
    public int maxPeriodsOn(int day) {
        int max = 0;
        for (ClassData cd : classes()) {
            max = Math.max(max, cd.periods()[day]);
        }
        return max;
    }

    // ------------------------------------------------------------ 提取用

    public List<Integer> assignIndicesOf(long classId) {
        return index.assignIndicesOf(classId);
    }

    public ScheduleInput.Assignment assignmentAt(int globalIndex) {
        return index.assignmentAt(globalIndex);
    }
}
