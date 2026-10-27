package com.orbit.assistant.model;

import java.time.LocalDateTime;
import java.util.Map;

public record ActionResponse(long id, long conversationId, int turnIndex, String toolName,
                             Map<String, Object> arguments, Map<String, Object> result,
                             Map<String, Object> proposal, String status,
                             Integer expectedRevision, Integer finalRevision,
                             String errorDetail, LocalDateTime createdAt,
                             LocalDateTime decidedAt) {
}
