package com.orbit.assistant.model;

import jakarta.validation.constraints.NotBlank;

import java.util.Map;

public record ChatRequest(Long conversationId,
                          @NotBlank(message = "Field required") String message,
                          String tool,
                          Map<String, Object> arguments) {
}
