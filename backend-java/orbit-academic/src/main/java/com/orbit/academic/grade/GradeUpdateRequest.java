package com.orbit.academic.grade;

import java.util.Map;

/**
 * 修改年级 —— 对应 Python 的 {@code GradeUpdate}。
 *
 * <p>两个字段都可选，语义是 **PATCH 而不是 PUT**：只有传了的字段会被改。
 * 这跟 HTTP 语义有出入（Python 用的是 PUT），但**行为要保持一致** ——
 * 前端只传 `{"name": "新名字"}` 时，`periods_per_day` 必须原样保留。
 *
 * <p>所以这里**不能**用「整体覆盖」的写法，Service 里要显式判断 null。
 */
public record GradeUpdateRequest(String name, Map<String, Integer> periodsPerDay) {
}
