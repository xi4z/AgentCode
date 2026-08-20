package com.agentcode.agent;

/**
 * 处理流式输出的类
 * @param status
 * @param content
 */
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
