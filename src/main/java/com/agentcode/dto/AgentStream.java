package com.agentcode.dto;

/**
 * 推送给前端的流式事件。seq 是持久会话事件的序号，用于断线重连。
 *
 * <p>同一 seq 可能产生多帧；前端按 seq 递增保存游标。
 */
public record AgentStream(Status status, String content, long seq) {

    /** 前端渲染所需的最小状态集合。 */
    public enum Status {
        TURN_STARTED,
        THINKING_STREAMING,
        THINKING_FINISHED,
        RESPONSE_STREAMING,
        RESPONSE_FINISHED,
        TOOL_STREAMING,
        TOOL_FINISHED,
        PERMISSION_REQUESTED,
        TURN_FINISHED,
        ERROR
    }
}
