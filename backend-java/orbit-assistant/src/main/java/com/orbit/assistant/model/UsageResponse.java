package com.orbit.assistant.model;

public record UsageResponse(long usedTokens, long dailyBudget, long remainingTokens,
                            boolean llmEnabled, String mode) {
}
