package com.agentcode.session;

/** 会话元数据：runId 与工作区的绑定，跨进程读取后用于恢复 AgentSession。 */
public record SessionMetadata(String runId, String workspace, long createdAt) {
}
