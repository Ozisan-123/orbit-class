package com.orbit.academic.grade;

import java.util.Map;

/** 年级视图 —— 对应 Python 的 {@code GradeOut}：{@code {id, name, periods_per_day}}。 */
public record GradeResponse(Long id, String name, Map<String, Integer> periodsPerDay) {

    public static GradeResponse from(Grade grade) {
        return new GradeResponse(grade.getId(), grade.getName(), grade.getPeriodsPerDay());
    }
}
