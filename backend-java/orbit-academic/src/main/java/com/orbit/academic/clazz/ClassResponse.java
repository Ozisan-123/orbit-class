package com.orbit.academic.clazz;

/**
 * 班级视图 —— 对应 Python 的 {@code ClassOut}。
 *
 * <pre>{@code {"id": 1, "name": "一年级1班", "grade_id": 1, "grade_name": "一年级"}}</pre>
 *
 * <p>{@code grade_name} 是**联出来的**（不在 classes 表里），Python 用 joinedload 取。
 * 这里用「批量查一次年级」实现，避免 N+1。
 */
public record ClassResponse(Long id, String name, Long gradeId, String gradeName) {

    public static ClassResponse of(Clazz clazz, String gradeName) {
        return new ClassResponse(clazz.getId(), clazz.getName(), clazz.getGradeId(), gradeName);
    }
}
