package com.orbit.assistant.model;

import java.time.LocalDateTime;

public record ConversationResponse(long id, String title, LocalDateTime createdAt,
                                   LocalDateTime updatedAt) {
}
