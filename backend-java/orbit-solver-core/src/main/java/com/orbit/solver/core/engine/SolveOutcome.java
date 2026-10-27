package com.orbit.solver.core.engine;

import com.google.ortools.sat.CpSolverStatus;
import com.orbit.solver.core.model.TimetableEntry;

import java.util.List;

/**
 * 求解结果。
 *
 * <p>刻意把 {@code status} 带出来：原 Python 实现把所有非 OPTIMAL/FEASIBLE 的情况
 * 都压成空列表返回，导致上层无法区分「超时」与「真的无解」——这是方案里要修的缺陷之一
 * （见 docs/智能教务中台-项目方案.md 的「不可行性诊断」）。
 */
public record SolveOutcome(
        CpSolverStatus status,
        List<TimetableEntry> entries,
        String modelStats,
        String modelFingerprint,
        double wallTimeSeconds,
        long objectiveValue,
        long bestObjectiveBound) {

    public boolean hasSolution() {
        return status == CpSolverStatus.OPTIMAL || status == CpSolverStatus.FEASIBLE;
    }

    public boolean timedOut() {
        return status == CpSolverStatus.UNKNOWN;
    }
}
