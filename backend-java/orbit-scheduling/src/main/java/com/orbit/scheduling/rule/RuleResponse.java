package com.orbit.scheduling.rule;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 规则列表项 —— {@code GET /api/rules} 的元素。
 *
 * <p>方案 §13.2 对它有明确要求：**必须含** {@code description}（人话描述）、
 * {@code scope_name}（"三年级"而不是 {@code 3}）、{@code handler_display_name}。
 * 原因是这个列表要直接给用户看 —— 一列 {@code rule_type=subject_max_per_day} 加
 * {@code scope=grade, scope_id=3} 对用户毫无意义。
 *
 * <h2>两处刻意的字段命名</h2>
 * <ul>
 *   <li>{@code revision} 而不是 {@code version}：数据库列叫 {@code version}（§5.3 定的），
 *       但**对外**这个值就是乐观锁，与 {@code schedule_versions.revision} 是同一件事，
 *       接口里统一叫 {@code revision}，请求体里就是 {@code expected_revision}。
 *       前端只需要记一套命名。</li>
 *   <li>{@code handler_display_name}：规则的"名字"其实来自 handler（开发者写的），
 *       不是来自数据行。分开暴露可以让前端把它当**类型标签**显示，
 *       而 {@code description} 是这条**规则实例**的说明。</li>
 * </ul>
 */
public record RuleResponse(
        Long id,
        String ruleType,
        String handlerDisplayName,
        Map<String, Object> params,
        String hardness,
        Integer weight,
        String scope,
        Long scopeId,
        String scopeName,
        Boolean enabled,
        String description,
        String source,
        Integer revision,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public static RuleResponse of(ConstraintRuleEntity entity,
                                  String handlerDisplayName,
                                  String scopeName,
                                  String description) {
        return new RuleResponse(
                entity.getId(),
                entity.getRuleType(),
                handlerDisplayName,
                entity.getParams() == null ? Map.of() : entity.getParams(),
                entity.getHardness(),
                entity.getWeight(),
                entity.getScope(),
                entity.getScopeId(),
                scopeName,
                entity.getEnabled(),
                description,
                entity.getSource(),
                entity.getVersion(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }
}
