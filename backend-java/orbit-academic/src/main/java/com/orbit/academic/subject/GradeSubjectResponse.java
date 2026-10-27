package com.orbit.academic.subject;

/** 年级课时视图 —— 对应 Python {@code GradeSubjectOut}。 */
public record GradeSubjectResponse(Long id, Long gradeId, Long subjectId,
                                   Integer weeklyHours, String subjectName) {
}
