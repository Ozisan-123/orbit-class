package com.orbit.assistant.security;

public record AssistantPrincipal(long userId, long organizationId, String username,
                                 String displayName, String role) {
    public boolean isAdmin() {
        return "admin".equals(role) || "platform_admin".equals(role);
    }
}
