package com.orbit.scheduling.rule;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 规则集版本 —— {@code GET /api/rules/versions} 与 {@code /versions/{id}}。
 *
 * <p>两个静态工厂对应两种粒度，这是**有意的**：
 * <ul>
 *   <li>{@link #summary} 列表用，**不带 {@code rules} 快照** —— 一次列出几十个版本时
 *       把每个版本的完整规则都塞进响应会非常大，而列表页只需要版本号/哈希/条数/说明</li>
 *   <li>{@link #detail} 详情用，带快照 —— 审计链要"点开看这一版到底用了哪些规则"</li>
 * </ul>
 * 这也是 {@code schedule_versions} 的既有做法（列表不返明细、详情返）。
 */
public record RuleVersionResponse(
        Long id,
        Integer versionNo,
        String ruleSetHash,
        Integer ruleCount,
        String note,
        LocalDateTime createdAt,
        /** 仅详情接口返回；列表为 null。 */
        List<Map<String, Object>> rules) {

    public static RuleVersionResponse summary(ConstraintRuleVersionEntity entity) {
        return new RuleVersionResponse(entity.getId(), entity.getVersionNo(),
                entity.getRuleSetHash(), entity.getRuleCount(), entity.getNote(),
                entity.getCreatedAt(), null);
    }

    public static RuleVersionResponse detail(ConstraintRuleVersionEntity entity) {
        return new RuleVersionResponse(entity.getId(), entity.getVersionNo(),
                entity.getRuleSetHash(), entity.getRuleCount(), entity.getNote(),
                entity.getCreatedAt(), entity.getRules());
    }
}
