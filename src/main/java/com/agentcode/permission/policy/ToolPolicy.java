package com.agentcode.permission.policy;

public record ToolPolicy(PermissionDecision defaultDecision, java.util.List<String> allowPatterns, java.util.List<String> denyPatterns) {
}
