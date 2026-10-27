package com.orbit.academic.assignment;

/** 任教分配视图 —— 对应 Python 的 {@code AssignmentOut}。 */
public record AssignmentResponse(Long id, Long teacherId, Long classId, Long subjectId,
                                 String teacherName, String className, String subjectName) {
}
