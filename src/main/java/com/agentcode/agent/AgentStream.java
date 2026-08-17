package com.agentcode.agent;

public record AgentStream(
        Status status,
        String content
) {
    public enum Status {
        THINKING_STREAMING,
        THINKING_FINISHED,
        RESPONSE_STREAMING,
        RESPONSE_FINISHED,
        TOOL_STREAMING,
        TOOL_FINISHED
    }



}
