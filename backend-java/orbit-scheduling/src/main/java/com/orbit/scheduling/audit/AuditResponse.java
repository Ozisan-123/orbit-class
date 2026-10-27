package com.orbit.scheduling.audit;

import java.time.LocalDateTime;
import java.util.Map;

/** 审计记录视图 —— 对应 Python 的 {@code ScheduleAuditOut}。 */
public record AuditResponse(Long id, String userName, String action,
                            Map<String, Object> details, LocalDateTime createdAt) {

    public static AuditResponse from(ScheduleAuditLog log) {
        return new AuditResponse(log.getId(), log.getUserName(), log.getAction(),
                // Python: details=item.details or {}
                log.getDetails() == null ? Map.of() : log.getDetails(),
                log.getCreatedAt());
    }
}
