package com.orbit.scheduling.diagnosis;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 诊断结果 —— {@code GET /api/schedule/diagnosis}。
 *
 * <h2>{@link #kind} 的三态是本期新增的能力</h2>
 * 排课接口对外只有一句 {@code 排课无解，请检查任教关系、周课时、固定时段和教师课时上限}
 * （那是 Python 的行为，P1 逐字保留了）。但求解器其实能区分两件**完全不同**的事：
 * <ul>
 *   <li>{@code INFEASIBLE} —— CP-SAT **证明了**无解。此时"改数据"是唯一出路</li>
 *   <li>{@code UNKNOWN} —— 到时限还没证明。可能解存在只是没找到，
 *       **延长时间/换参数就行，不该去改数据**</li>
 * </ul>
 * 把两者混成一句话，会让用户去改本来没问题的数据。所以这里必须分开报。
 *
 * @param staticFindings 静态检查的结果（不求解，毫秒级）
 * @param conflictRules  **最小冲突集**：去掉它就能排出课表的那些硬规则（深诊断才有）
 * @param note           结果的可信度说明 —— 明确写出"没找到"与"排不出来"是两回事
 */
public record DiagnosisResponse(
        String kind,
        String severity,
        Long versionId,
        String jobToken,
        String ruleSetHash,
        /** 静态检查发现的问题（按严重度排序）。 */
        List<DiagnosisFinding> staticFindings,
        /** 深诊断放宽后能求解的硬规则 → 冲突就出在这些规则里。 */
        List<ConflictRule> conflictRules,
        /** 本次是否真的做了深诊断。 */
        boolean deep,
        String note,
        LocalDateTime createdAt) {

    /** 一条"去掉它就能排出来"的硬规则。 */
    public record ConflictRule(Long ruleId, String ruleType, String description) {
    }

    public static final String KIND_OK = "OK";
    public static final String KIND_INFEASIBLE = "INFEASIBLE";
    public static final String KIND_UNKNOWN = "UNKNOWN";
}
