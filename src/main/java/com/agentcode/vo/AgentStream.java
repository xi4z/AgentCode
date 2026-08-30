package com.agentcode.vo;

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
        TOOL_FINISHED,
        PERMISSION_REQUESTED,
        /** 本轮仍有未答复的审批项，服务端已记录本次决定但暂不恢复执行 */
        PERMISSION_PENDING
    }



}
