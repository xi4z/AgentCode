package com.agentcode.session;

/**
 * 一条待审批的工具调用。
 *
 * @param requestId AgentScope 的审批请求标识，respond 时使用
 * @param toolCallId 模型生成的工具调用标识，前端按它回传决定
 * @param toolName 工具名
 * @param arguments 参数 JSON
 * @param description 展示给用户的审批说明
 */
public record PendingPermission(
        String requestId,
        String toolCallId,
        String toolName,
        String arguments,
        String description) {
}
