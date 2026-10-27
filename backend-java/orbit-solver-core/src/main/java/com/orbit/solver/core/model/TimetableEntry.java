package com.orbit.solver.core.model;

/** 求解结果的一行 —— 对标 Python {@code results} 里的 dict。 */
public record TimetableEntry(
        long classId,
        long subjectId,
        long teacherId,
        int dayOfWeek,
        int periodIndex) {
}
