package com.agentcode.websocket;

import com.agentcode.dto.AgentInterruptHandle;
import com.agentcode.dto.AgentStream;
import com.agentcode.service.ReactAgentService;
import com.agentcode.websocket.ChatProtocol.ClientMessage;
import com.agentcode.websocket.ChatProtocol.PermissionHandle;
import com.agentcode.websocket.ChatProtocol.ServerMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * WebSocket 端点：/ws/chat
 *
 * 支持：
 * - start_session：创建新会话，返回 runId
 * - chat：基于已有 runId 继续多轮对话
 * - stop：停止当前任务
 * - interrupt：打断当前思考并给出引导
 * - permission_respond：响应服务器发起的权限审批请求
 * - ping：心跳
 */
@Component
@RequiredArgsConstructor
public class ChatWebSocketHandler implements WebSocketHandler {

    private final ReactAgentService agentService;
    private final ObjectMapper objectMapper;

    /**
     * key: connectionId:runId
     * value: 当前该会话正在运行的订阅
     */
    private final Map<String, Disposable> subscriptions = new ConcurrentHashMap<>();

    @Override
    public Mono<Void> handle(WebSocketSession session) {
        String connectionId = session.getId();
        Sinks.Many<String> outbound = Sinks.many()
                .unicast()
                .onBackpressureBuffer();

        Mono<Void> output = session.send(
                outbound.asFlux().map(session::textMessage)
        );

        Mono<Void> input = session.receive()
                .map(WebSocketMessage::getPayloadAsText)
                .doOnNext(payload -> handleClientMessage(session, payload, outbound))
                .then()
                .doFinally(signal -> outbound.tryEmitComplete());

        return Mono.when(output, input)
                .doFinally(signal -> cleanupConnection(connectionId));
    }

    private void handleClientMessage(WebSocketSession session, String payload, Sinks.Many<String> outbound) {
        ClientMessage message;
        try {
            message = objectMapper.readValue(payload, ClientMessage.class);
        } catch (Exception e) {
            send(outbound, ServerMessage.error(null, null, "无法解析消息: " + e.getMessage()));
            return;
        }

        if (message.type() == null) {
            send(outbound, ServerMessage.error(message.requestId(), null, "缺少 type 字段"));
            return;
        }

        switch (message.type()) {
            case "start_session" -> startSession(session, message, outbound);
            case "chat" -> chat(session, message, outbound);
            case "stop" -> stop(message, outbound);
            case "interrupt" -> interrupt(message, outbound);
            case "permission_respond" -> permissionRespond(session, message, outbound);
            case "ping" -> send(outbound, ServerMessage.pong(message.requestId()));
            default -> send(outbound, ServerMessage.error(message.requestId(), null,
                    "未知消息类型: " + message.type()));
        }
    }

    private void startSession(WebSocketSession session, ClientMessage message, Sinks.Many<String> outbound) {
        if (message.goal() == null || message.goal().isBlank()) {
            send(outbound, ServerMessage.error(message.requestId(), null, "start_session 需要 goal"));
            return;
        }

        String runId = agentService.createSession(message.goal(), message.workspace());

        send(outbound, ServerMessage.sessionStarted(message.requestId(), runId));
        subscribeAgent(session, message.requestId(), runId, message.goal(), outbound);
    }

    private void chat(WebSocketSession session, ClientMessage message, Sinks.Many<String> outbound) {
        if (message.runId() == null || message.runId().isBlank()) {
            send(outbound, ServerMessage.error(message.requestId(), null, "chat 需要 runId"));
            return;
        }
        if (!agentService.sessionExists(message.runId())) {
            send(outbound, ServerMessage.error(message.requestId(), message.runId(), "会话不存在: " + message.runId()));
            return;
        }
        if (message.content() == null || message.content().isBlank()) {
            send(outbound, ServerMessage.error(message.requestId(), message.runId(), "chat 需要 content"));
            return;
        }

        subscribeAgent(session, message.requestId(), message.runId(), message.content(), outbound);
    }

    private void subscribeAgent(WebSocketSession session, String requestId, String runId, String goal,
                                Sinks.Many<String> outbound) {
        subscribe(session, requestId, runId, agentService.run(goal, runId), outbound);
    }

    private void subscribe(WebSocketSession session, String requestId, String runId,
                           Flux<AgentStream> stream, Sinks.Many<String> outbound) {
        String connectionId = session == null ? null : session.getId();
        String key = connectionId == null ? ":" + runId : connectionId + ":" + runId;

        Disposable disposable;
        AtomicReference<Disposable> self = new AtomicReference<>();
        try {
            // 等待用户输入（审批请求或“还没答复齐”）时不能给前端发 done，否则客户端会认为本轮已结束
            AtomicBoolean awaitingUser = new AtomicBoolean(false);
            disposable = stream
                    .doOnNext(event -> {
                        if (event.status() == AgentStream.Status.PERMISSION_REQUESTED) {
                            awaitingUser.set(true);
                            sendPermissionRequests(outbound, requestId, runId, event.content());
                        } else if (event.status() == AgentStream.Status.PERMISSION_PENDING) {
                            awaitingUser.set(true);
                            send(outbound, ServerMessage.permissionPending(requestId, runId, event.content()));
                        } else {
                            send(outbound, ServerMessage.agentEvent(runId, event));
                        }
                    })
                    .doOnComplete(() -> {
                        if (!awaitingUser.get()) {
                            send(outbound, ServerMessage.done(runId));
                        }
                    })
                    .doOnError(error -> send(outbound, ServerMessage.error(requestId, runId, error.getMessage())))
                    .doFinally(signal -> {
                        // 只摘掉自己这条订阅，避免误删同 key 的新订阅
                        Disposable mine = self.get();
                        if (mine != null) {
                            subscriptions.remove(key, mine);
                        }
                    })
                    .subscribe();
        } catch (Exception e) {
            send(outbound, ServerMessage.error(requestId, runId, e.getMessage()));
            return;
        }

        self.set(disposable);
        if (disposable.isDisposed()) {
            // 同步就跑完的流（例如只回 PERMISSION_PENDING 的答复）不必登记，否则会残留已完成条目
            return;
        }
        Disposable previous = subscriptions.put(key, disposable);
        if (previous != null && previous != disposable && !previous.isDisposed()) {
            previous.dispose();
        }
        if (disposable.isDisposed()) {
            subscriptions.remove(key, disposable);
        }
    }

    private void sendPermissionRequests(Sinks.Many<String> outbound, String requestId, String runId,
                                        String permissionJson) {
        try {
            JsonNode array = objectMapper.readTree(permissionJson);
            if (array.isArray()) {
                for (JsonNode node : array) {
                    send(outbound, ServerMessage.permissionRequested(
                            requestId,
                            runId,
                            node.path("toolCallId").asText(null),
                            node.path("toolName").asText(null),
                            node.path("arguments").asText(null),
                            node.path("description").asText(null)
                    ));
                }
            }
        } catch (Exception e) {
            send(outbound, ServerMessage.error(requestId, runId, "权限请求解析失败: " + e.getMessage()));
        }
    }

    /**
     * 处理审批答复。支持两种写法：
     * <ul>
     *   <li>单个：顶层 {@code toolCallId + decision}（老客户端）</li>
     *   <li>批量：{@code handles: [{toolCallId, decision, ...}]}，一次提交本轮全部决定</li>
     * </ul>
     * 服务端会缓存决定，等本轮待审批项全部答复后才恢复 Agent 执行。
     */
    private void permissionRespond(WebSocketSession session, ClientMessage message, Sinks.Many<String> outbound) {
        if (message.runId() == null || message.runId().isBlank()) {
            send(outbound, ServerMessage.error(message.requestId(), message.runId(), "permission_respond 需要 runId"));
            return;
        }

        List<PermissionHandle> submitted;
        if (message.handles() != null && !message.handles().isEmpty()) {
            submitted = message.handles();
        } else {
            submitted = List.of(new PermissionHandle(
                    message.toolCallId(), message.toolName(), message.arguments(),
                    null, message.decision(), message.feedback()));
        }

        AgentInterruptHandle[] handles = new AgentInterruptHandle[submitted.size()];
        for (int i = 0; i < submitted.size(); i++) {
            PermissionHandle item = submitted.get(i);
            if (item == null || item.toolCallId() == null || item.toolCallId().isBlank()
                    || item.decision() == null || item.decision().isBlank()) {
                send(outbound, ServerMessage.error(message.requestId(), message.runId(),
                        "permission_respond 需要每个审批项的 toolCallId 与 decision"));
                return;
            }
            AgentInterruptHandle.Decision decision;
            try {
                decision = AgentInterruptHandle.Decision.valueOf(item.decision().trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                send(outbound, ServerMessage.error(message.requestId(), message.runId(),
                        "未知 decision: " + item.decision() + "，可选 APPROVED/APPROVE_ALL/REJECTED/EDITED"));
                return;
            }
            handles[i] = new AgentInterruptHandle(
                    message.runId(),
                    item.toolCallId(),
                    item.toolName(),
                    item.arguments(),
                    item.description(),
                    decision,
                    item.feedback()
            );
        }

        try {
            subscribe(session, message.requestId(), message.runId(),
                    agentService.handleInterrupt(message.runId(), handles), outbound);
        } catch (Exception e) {
            send(outbound, ServerMessage.error(message.requestId(), message.runId(), e.getMessage()));
        }
    }

    private void stop(ClientMessage message, Sinks.Many<String> outbound) {
        if (message.runId() == null || message.runId().isBlank()) {
            send(outbound, ServerMessage.error(message.requestId(), null, "stop 需要 runId"));
            return;
        }
        try {
            agentService.stop(message.runId());
            send(outbound, ServerMessage.stopped(message.requestId(), message.runId()));
        } catch (Exception e) {
            send(outbound, ServerMessage.error(message.requestId(), message.runId(), e.getMessage()));
        }
    }

    private void interrupt(ClientMessage message, Sinks.Many<String> outbound) {
        if (message.runId() == null || message.runId().isBlank()) {
            send(outbound, ServerMessage.error(message.requestId(), null, "interrupt 需要 runId"));
            return;
        }
        try {
            agentService.interrupt(message.runId(), message.guidance());
            send(outbound, ServerMessage.interrupted(message.requestId(), message.runId()));
        } catch (Exception e) {
            send(outbound, ServerMessage.error(message.requestId(), message.runId(), e.getMessage()));
        }
    }

    private void cleanupConnection(String connectionId) {
        subscriptions.entrySet().removeIf(entry -> {
            boolean belongs = entry.getKey().startsWith(connectionId + ":");
            if (belongs) {
                entry.getValue().dispose();
            }
            return belongs;
        });
    }

    private void send(Sinks.Many<String> outbound, ServerMessage message) {
        try {
            outbound.tryEmitNext(objectMapper.writeValueAsString(message));
        } catch (JsonProcessingException e) {
            outbound.tryEmitNext("{\"type\":\"error\",\"message\":\"消息序列化失败\"}");
        }
    }
}
