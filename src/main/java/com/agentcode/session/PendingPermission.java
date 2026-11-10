package com.agentcode.session;

public record PendingPermission(
        String requestId,
        String toolCallId,
        String toolName,
        String arguments,
        String description) {
}
