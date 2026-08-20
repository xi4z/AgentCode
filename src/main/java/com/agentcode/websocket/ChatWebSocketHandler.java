package com.agentcode.websocket;

import com.agentcode.agent.AgentInterruptHandle;
import com.agentcode.agent.AgentStream;
import com.agentcode.context.AgentContext;
import com.agentcode.service.ReactAgentService;
import com.agentcode.store.InMemoryAgentContextStore;
import com.agentcode.websocket.ChatProtocol.ClientMessage;
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

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

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
    private final InMemoryAgentContextStore contextStore;
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

        String runId = UUID.randomUUID().toString();
        AgentContext context = AgentContext.builder()
                .runId(runId)
                .goal(message.goal())
                .workspace(message.workspace())
                .build();
        contextStore.save(runId, context);

        send(outbound, ServerMessage.sessionStarted(message.requestId(), runId));
        subscribeAgent(session, message.requestId(), runId, message.goal(), outbound);
    }

    private void chat(WebSocketSession session, ClientMessage message, Sinks.Many<String> outbound) {
        if (message.runId() == null || message.runId().isBlank()) {
            send(outbound, ServerMessage.error(message.requestId(), null, "chat 需要 runId"));
            return;
        }
        if (contextStore.find(message.runId()).isEmpty()) {
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
        try {
            AtomicBoolean permissionRequested = new AtomicBoolean(false);
            disposable = stream
                    .doOnNext(event -> {
                        if (event.status() == AgentStream.Status.PERMISSION_REQUESTED) {
                            permissionRequested.set(true);
                            sendPermissionRequests(outbound, requestId, runId, event.content());
                        } else {
                            send(outbound, ServerMessage.agentEvent(runId, event));
                        }
                    })
                    .doOnComplete(() -> {
                        if (!permissionRequested.get()) {
                            send(outbound, ServerMessage.done(runId));
                        }
                    })
                    .doOnError(error -> send(outbound, ServerMessage.error(requestId, runId, error.getMessage())))
                    .doFinally(signal -> subscriptions.remove(key))
                    .subscribe();
        } catch (Exception e) {
            send(outbound, ServerMessage.error(requestId, runId, e.getMessage()));
            return;
        }

        Disposable previous = subscriptions.put(key, disposable);
        if (previous != null && !previous.isDisposed()) {
            previous.dispose();
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

    private void permissionRespond(WebSocketSession session, ClientMessage message, Sinks.Many<String> outbound) {
        if (message.runId() == null || message.runId().isBlank()
                || message.toolCallId() == null || message.toolCallId().isBlank()
                || message.decision() == null || message.decision().isBlank()) {
            send(outbound, ServerMessage.error(message.requestId(), message.runId(),
                    "permission_respond 需要 runId、toolCallId、decision"));
            return;
        }

        AgentInterruptHandle.Decision decision;
        try {
            decision = AgentInterruptHandle.Decision.valueOf(message.decision().trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            send(outbound, ServerMessage.error(message.requestId(), message.runId(),
                    "未知 decision: " + message.decision() + "，可选 APPROVED/APPROVE_ALL/REJECTED/EDITED"));
            return;
        }

        AgentInterruptHandle handle = new AgentInterruptHandle(
                message.runId(),
                message.toolCallId(),
                message.toolName(),
                message.arguments(),
                null,
                decision,
                message.feedback()
        );

        try {
            subscribe(session, message.requestId(), message.runId(),
                    agentService.handleInterrupt(handle), outbound);
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
