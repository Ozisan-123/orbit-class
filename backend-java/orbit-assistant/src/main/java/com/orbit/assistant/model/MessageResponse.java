package com.orbit.assistant.model;

import java.time.LocalDateTime;

public record MessageResponse(long id, long conversationId, int turnIndex, String role,
                              String content, int tokenCount, LocalDateTime createdAt) {
}
