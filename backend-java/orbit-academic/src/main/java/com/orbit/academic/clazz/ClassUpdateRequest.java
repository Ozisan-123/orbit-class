package com.orbit.academic.clazz;

/**
 * 修改班级 —— 对应 Python 的 {@code ClassUpdate}：{@code {name?: str, grade_id?: int}}。
 *
 * <p>同 {@code GradeUpdateRequest}：PUT 路径但**语义是局部更新**
 * （只有非 null 的字段会被改），必须保持一致。
 */
public record ClassUpdateRequest(String name, Long gradeId) {
}
