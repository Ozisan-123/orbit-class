package com.orbit.academic.assignment;

import java.util.List;

/**
 * 自动分配请求 —— 对应 Python {@code AssignmentAutoRequest}。
 *
 * <pre>{@code
 * replace_existing: bool = False
 * subject_ids: list[int] = []
 * }</pre>
 *
 * <p>两个字段都可省：{@code replaceExisting} 缺省 false（保留已有分配），
 * {@code subjectIds} 缺省空列表（表示**不限定科目**，全部科目都参与）。
 * 默认值在 Service 里落实。
 */
public record AssignmentAutoRequest(Boolean replaceExisting, List<Long> subjectIds) {
}
