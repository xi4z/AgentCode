package com.agentcode.websocket;

import com.agentcode.dto.AgentStream;
import com.agentcode.session.AgentChatService;
import com.agentcode.session.PendingPermission;
import com.agentcode.websocket.ChatProtocol.ClientMessage;
import com.agentcode.websocket.ChatProtocol.ServerMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * WebSocket 端点：/ws/chat。
 *
 * <p>连接只负责观察持久会话。断开连接不会停止 AgentScope 的会话执行，
 * 重新连接后可以用 attach 从指定序号继续读取事件。
 */
@Component
public class ChatWebSocketHandler implements WebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(ChatWebSocketHandler.class);

    private final AgentChatService chatService;
    private final ObjectMapper objectMapper;

    /** key: connectionId:runId, value: 当前事件订阅 */
    private final Map<String, Disposable> subscriptions = new ConcurrentHashMap<>();

    public ChatWebSocketHandler(AgentChatService chatService, ObjectMapper objectMapper) {
        this.chatService = chatService;
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<Void> handle(WebSocketSession session) {
        String connectionId = session.getId();
        Sinks.Many<String> outbound = Sinks.many().unicast().onBackpressureBuffer();

        Mono<Void> output = session.send(outbound.asFlux().map(session::textMessage));
        Mono<Void> input =
                session.receive()
                        .map(WebSocketMessage::getPayloadAsText)
                        .doOnNext(
                                payload ->
                                        handleClientMessage(
                                                connectionId, session, payload, outbound))
                        .then()
                        .doFinally(signal -> outbound.tryEmitComplete());

        return Mono.when(output, input).doFinally(signal -> cleanupConnection(connectionId));
    }

    private void handleClientMessage(
            String connectionId,
            WebSocketSession session,
            String payload,
            Sinks.Many<String> outbound) {
        ClientMessage message;
        try {
            message = objectMapper.readValue(payload, ClientMessage.class);
        } catch (Exception error) {
            send(outbound, ServerMessage.error(null, null, "无法解析消息: " + error.getMessage()));
            return;
        }
        if (message.type() == null) {
            send(outbound, ServerMessage.error(message.requestId(), null, "缺少 type 字段"));
            return;
        }
        try {
            switch (message.type()) {
                case "start_session" -> startSession(connectionId, message, outbound);
                case "chat" -> chat(connectionId, message, outbound);
                case "attach" -> attach(connectionId, message, outbound);
                case "permission_respond" -> permissionRespond(connectionId, message, outbound);
                case "stop" -> stop(message, outbound);
                case "interrupt" -> interrupt(message, outbound);
                case "ping" -> send(outbound, ServerMessage.pong(message.requestId()));
                default ->
                        send(
                                outbound,
                                ServerMessage.error(
                                        message.requestId(), null, "未知消息类型: " + message.type()));
            }
        } catch (RuntimeException error) {
            log.warn("处理 WebSocket 消息失败: type={}", message.type(), error);
            send(
                    outbound,
                    ServerMessage.error(
                            message.requestId(), message.runId(), error.getMessage()));
        }
    }

    private void startSession(
            String connectionId, ClientMessage message, Sinks.Many<String> outbound) {
        AgentChatService.SessionStart start =
                chatService.startSession(message.goal(), message.workspace(), message.requestId());
        send(outbound, ServerMessage.sessionStarted(message.requestId(), start.runId()));
        subscribe(
                connectionId,
                message.requestId(),
                start.runId(),
                start.afterSeq(),
                start.turnId(),
                outbound);
    }

    private void chat(String connectionId, ClientMessage message, Sinks.Many<String> outbound) {
        if (message.runId() == null || message.runId().isBlank()) {
            throw new IllegalArgumentException("chat 需要 runId");
        }
        AgentChatService.Submission submission =
                chatService.chat(message.runId(), message.content(), message.requestId());
        subscribe(
                connectionId,
                message.requestId(),
                message.runId(),
                submission.afterSeq(),
                submission.turnId(),
                outbound);
    }

    private void attach(String connectionId, ClientMessage message, Sinks.Many<String> outbound) {
        if (message.runId() == null || message.runId().isBlank()) {
            throw new IllegalArgumentException("attach 需要 runId");
        }
        long afterSeq = message.afterSeq() == null ? 0L : message.afterSeq();
        subscribe(connectionId, message.requestId(), message.runId(), afterSeq, null, outbound);
    }

    private void permissionRespond(
            String connectionId, ClientMessage message, Sinks.Many<String> outbound) {
        if (message.runId() == null || message.runId().isBlank()) {
            throw new IllegalArgumentException("permission_respond 需要 runId");
        }
        if (message.toolCallId() == null || message.toolCallId().isBlank()) {
            throw new IllegalArgumentException("permission_respond 需要 toolCallId");
        }
        if (message.decision() == null || message.decision().isBlank()) {
            throw new IllegalArgumentException("permission_respond 需要 decision");
        }
        AgentChatService.Submission submission =
                chatService.respond(
                        message.runId(),
                        message.toolCallId(),
                        message.decision().trim().toUpperCase(),
                        message.arguments(),
                        message.feedback());
        subscribe(
                connectionId,
                message.requestId(),
                message.runId(),
                submission.afterSeq(),
                submission.turnId(),
                outbound);
    }

    private void stop(ClientMessage message, Sinks.Many<String> outbound) {
        if (message.runId() == null || message.runId().isBlank()) {
            throw new IllegalArgumentException("stop 需要 runId");
        }
        chatService.stop(message.runId());
        send(outbound, ServerMessage.stopped(message.requestId(), message.runId()));
    }

    private void interrupt(ClientMessage message, Sinks.Many<String> outbound) {
        if (message.runId() == null || message.runId().isBlank()) {
            throw new IllegalArgumentException("interrupt 需要 runId");
        }
        chatService.interrupt(message.runId(), message.guidance());
        send(outbound, ServerMessage.interrupted(message.requestId(), message.runId()));
    }

    private void subscribe(
            String connectionId,
            String requestId,
            String runId,
            long afterSeq,
            String expectedTurn,
            Sinks.Many<String> outbound) {
        String key = connectionId + ":" + runId;
        AtomicBoolean permissionRequested = new AtomicBoolean(false);
        AtomicBoolean failed = new AtomicBoolean(false);
        AtomicReference<Disposable> self = new AtomicReference<>();
        Disposable disposable =
                chatService
                        .stream(runId, afterSeq, expectedTurn)
                        .doOnNext(
                                event -> {
                                    if (event.status() == AgentStream.Status.PERMISSION_REQUESTED) {
                                        permissionRequested.set(true);
                                        sendPendingPermissions(outbound, requestId, runId);
                                    } else {
                                        if (event.status() == AgentStream.Status.ERROR) {
                                            failed.set(true);
                                        }
                                        send(
                                                outbound,
                                                ServerMessage.agentEvent(
                                                        runId, event.seq(), event));
                                    }
                                })
                        .doOnComplete(
                                () -> {
                                    if (!permissionRequested.get() && !failed.get()) {
                                        send(outbound, ServerMessage.done(runId));
                                    }
                                })
                        .doOnError(
                                error -> {
                                    log.warn("会话事件流失败: runId={}", runId, error);
                                    send(
                                            outbound,
                                            ServerMessage.error(
                                                    requestId, runId, error.getMessage()));
                                })
                        .doFinally(
                                signal -> {
                                    Disposable current = self.get();
                                    if (current != null) {
                                        subscriptions.remove(key, current);
                                    }
                                })
                        .subscribe();

        self.set(disposable);
        Disposable previous = subscriptions.put(key, disposable);
        if (disposable.isDisposed()) {
            subscriptions.remove(key, disposable);
        }
        if (previous != null && !previous.isDisposed()) {
            previous.dispose();
        }
    }

    private void sendPendingPermissions(
            Sinks.Many<String> outbound, String requestId, String runId) {
        for (PendingPermission permission : chatService.pending(runId)) {
            send(
                    outbound,
                    ServerMessage.permissionRequested(
                            requestId,
                            runId,
                            permission.toolCallId(),
                            permission.toolName(),
                            permission.arguments(),
                            permission.description()));
        }
    }

    private void cleanupConnection(String connectionId) {
        subscriptions
                .entrySet()
                .removeIf(
                        entry -> {
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
        } catch (Exception error) {
            log.warn("消息序列化失败", error);
            outbound.tryEmitNext("{\"type\":\"error\",\"message\":\"消息序列化失败\"}");
        }
    }
}
