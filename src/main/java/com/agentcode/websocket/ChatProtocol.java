package com.agentcode.websocket;

import com.agentcode.dto.AgentStream;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * WebSocket 持续对话使用的 JSON 协议。
 * 所有消息都通过 type 区分，其余字段按消息类型按需填写。
 */
public final class ChatProtocol {

    private ChatProtocol() {
    }

    /** 客户端 -> 服务端 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ClientMessage(
            String type,
            String requestId,
            String goal,
            String workspace,
            String runId,
            String content,
            String guidance,
            String toolCallId,
            String toolName,
            String decision,
            String arguments,
            String feedback,
            /** permission_respond 的批量形式：一次提交本轮全部审批决定 */
            List<PermissionHandle> handles
    ) {
    }

    /** permission_respond 中单个工具的审批决定 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PermissionHandle(
            String toolCallId,
            String toolName,
            String arguments,
            String description,
            String decision,
            String feedback
    ) {
    }

    /** 服务端 -> 客户端 */
    public record ServerMessage(
            String type,
            String requestId,
            String runId,
            String status,
            String content,
            String message,
            String toolCallId,
            String toolName,
            String arguments,
            String description
    ) {
        public static ServerMessage sessionStarted(String requestId, String runId) {
            return new ServerMessage("session_started", requestId, runId, null, null, null,
                    null, null, null, null);
        }

        public static ServerMessage agentEvent(String runId, AgentStream event) {
            return new ServerMessage("agent_event", null, runId, event.status().name(), event.content(), null,
                    null, null, null, null);
        }

        public static ServerMessage done(String runId) {
            return new ServerMessage("done", null, runId, null, null, null,
                    null, null, null, null);
        }

        public static ServerMessage stopped(String requestId, String runId) {
            return new ServerMessage("stopped", requestId, runId, null, null, null,
                    null, null, null, null);
        }

        public static ServerMessage interrupted(String requestId, String runId) {
            return new ServerMessage("interrupted", requestId, runId, null, null, null,
                    null, null, null, null);
        }

        public static ServerMessage permissionRequested(String requestId, String runId,
                                                        String toolCallId, String toolName,
                                                        String arguments, String description) {
            return new ServerMessage("permission_requested", requestId, runId, null, null, null,
                    toolCallId, toolName, arguments, description);
        }

        /**
         * 本轮仍有未答复的审批项：已记录本次决定，等答复齐后才会恢复执行。
         *
         * @param content 尚未答复的 toolCallId JSON 数组
         */
        public static ServerMessage permissionPending(String requestId, String runId, String content) {
            return new ServerMessage("permission_pending", requestId, runId, null, content, null,
                    null, null, null, null);
        }

        public static ServerMessage error(String requestId, String runId, String message) {
            return new ServerMessage("error", requestId, runId, null, null, message,
                    null, null, null, null);
        }

        public static ServerMessage pong(String requestId) {
            return new ServerMessage("pong", requestId, null, null, null, null,
                    null, null, null, null);
        }
    }
}
