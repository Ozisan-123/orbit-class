package com.orbit.solver.core.engine;

import com.google.ortools.sat.CpSolver;
import com.google.ortools.sat.IntVar;
import com.orbit.solver.core.model.ClassData;
import com.orbit.solver.core.model.ScheduleInput;
import com.orbit.solver.core.model.TimetableEntry;

import java.util.ArrayList;
import java.util.List;

/**
 * 把 CP-SAT 的求解结果还原成课表行。
 *
 * <p>关键点是**空堂哨兵**：时段变量的取值范围是 {@code 0..n}，
 * 其中 {@code 0..n-1} 表示第几条任教关系，而 {@code n} 表示这个时段没有课。
 */
public final class TimetableExtractor {

    private static final int DAYS = 5;

    public static List<TimetableEntry> extract(CpSolver solver, SlotVariableIndex index) {
        List<TimetableEntry> out = new ArrayList<>();
        for (ClassData cd : index.classDataInOrder()) {
            long classId = cd.classId();
            List<Integer> indices = index.assignIndicesOf(classId);
            if (indices.isEmpty()) {
                continue;
            }
            for (int day = 0; day < DAYS; day++) {
                for (int period = 0; period < cd.periods()[day]; period++) {
                    long subjectId;
                    long teacherId;
                    SlotVariableIndex.FixedEntry fixedEntry = index.fixedAt(classId, day, period);
                    if (fixedEntry != null) {
                        subjectId = fixedEntry.subjectId();
                        teacherId = fixedEntry.teacherId();
                    } else {
                        IntVar v = index.slotVar(classId, day, period);
                        if (v == null) {
                            continue;
                        }
                        long localIdx = solver.value(v);
                        if (localIdx >= indices.size()) {
                            continue;   // 空堂哨兵
                        }
                        ScheduleInput.Assignment a =
                                index.assignmentAt(indices.get((int) localIdx));
                        subjectId = a.subjectId();
                        teacherId = a.teacherId();
                    }
                    out.add(new TimetableEntry(classId, subjectId, teacherId, day, period));
                }
            }
        }
        return out;
    }

    private TimetableExtractor() {
    }
}
