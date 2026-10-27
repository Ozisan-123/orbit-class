package com.orbit.academic.subject;

/**
 * 科目视图 —— 对应 Python 的 {@code SubjectOut}。
 *
 * <pre>{@code {"id": 1, "name": "语文", "category": "major"}}</pre>
 *
 * <p>只有三个字段：Python 的 {@code SubjectOut} 就只有这三个，
 * **不返回** {@code organization_id}（虽然表里有 —— 当前用户本来就在这个组织里，返回它是冗余的）。
 * 字段名靠全局 {@code SNAKE_CASE} 策略输出。
 */
public record SubjectResponse(Long id, String name, String category) {

    public static SubjectResponse from(Subject subject) {
        return new SubjectResponse(subject.getId(), subject.getName(), subject.getCategory());
    }
}
