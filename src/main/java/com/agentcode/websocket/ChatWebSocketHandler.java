package com.agentcode.websocket;

import com.agentcode.agent.AgentSession;
import com.agentcode.dto.AgentInterruptHandle;
import com.agentcode.registry.AgentSessionRegistry;
import com.agentcode.vo.AgentStream;
import com.agentcode.service.ReactAgentService;
import com.agentcode.websocket.ChatProtocol.ClientMessage;
import com.agentcode.websocket.ChatProtocol.PermissionHandle;
import com.agentcode.websocket.ChatProtocol.ServerMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatWebSocketHandler implements WebSocketHandler {

    private final ReactAgentService agentService;
    private final ObjectMapper objectMapper;
    private final AgentSessionRegistry agentSessionRegistry;

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

        // createSession 内部含 JDBC insert，阻塞调用必须移出 Reactor event loop。
        // 注意：session_started 不能在这里发——会话要等 subscribeAgent 里 agentService.run()
        // 真正注册进 Registry 之后才算可用，否则紧随其后的 stop/interrupt 会撞上
        // SessionNotFoundException 竞态（实测踩过）
        Mono.fromCallable(() -> agentService.createSession(message.goal(), message.workspace()))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        runId -> subscribeAgent(session, message.requestId(), runId, message.goal(), outbound, true),
                        error -> send(outbound, ServerMessage.error(message.requestId(), null,
                                "创建会话失败: " + error.getMessage()))
                );
    }

    private void chat(WebSocketSession session, ClientMessage message, Sinks.Many<String> outbound) {
        if (message.runId() == null || message.runId().isBlank()) {
            send(outbound, ServerMessage.error(message.requestId(), null, "chat 需要 runId"));
            return;
        }
        if (message.content() == null || message.content().isBlank()) {
            send(outbound, ServerMessage.error(message.requestId(), message.runId(), "chat 需要 content"));
            return;
        }

        // sessionExists 内部是同步 JDBC select，移到 boundedElastic 执行
        Mono.fromCallable(() -> agentService.sessionExists(message.runId()))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        exists -> {
                            if (!exists) {
                                send(outbound, ServerMessage.error(message.requestId(), message.runId(),
                                        "会话不存在: " + message.runId()));
                                return;
                            }
                            subscribeAgent(session, message.requestId(), message.runId(), message.content(), outbound, false);
                        },
                        error -> send(outbound, ServerMessage.error(message.requestId(), message.runId(),
                                "会话校验失败: " + error.getMessage()))
                );
    }

    private void subscribeAgent(WebSocketSession session, String requestId, String runId, String goal,
                                Sinks.Many<String> outbound, boolean emitStarted) {
        // run() 的装配阶段可能触发 JDBC（加载/创建会话上下文），同样移出 event loop。
        // agentService.run() 返回即代表会话已注册进 Registry，此时发 session_started 才安全
        Mono.fromCallable(() -> agentService.run(goal, runId))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        stream -> {
                            if (emitStarted) {
                                send(outbound, ServerMessage.sessionStarted(requestId, runId));
                            }
                            subscribe(session, requestId, runId, stream, outbound);
                        },
                        error -> send(outbound, ServerMessage.error(requestId, runId, error.getMessage()))
                );
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
            // stop() 主动停止时 AgentSession 会先发 STOPPED 事件再完成 sink，
            // 此时不能补发 done，否则客户端会在 stopped 之前收到假 done
            AtomicBoolean stoppedByServer = new AtomicBoolean(false);
            disposable = stream
                    .doOnNext(event -> {
                        if (event.status() == AgentStream.Status.STOPPED) {
                            stoppedByServer.set(true);
                            return;
                        }
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
                        if (!awaitingUser.get() && !stoppedByServer.get()) {
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
        // stop 内部会查持久化上下文（JDBC），移到 boundedElastic。
        // 注意：fromRunnable 是空完成的 Mono，成功路径必须用 complete 回调发 stopped，
        // 不能用 onNext 消费者（永远不会被调用，客户端会永远等不到响应）
        log.info("AUDIT_WS_STOP_BEGIN runId={}", message.runId());
        Mono.fromRunnable(() -> agentService.stop(message.runId()))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        null,
                        error -> {
                            log.info("AUDIT_WS_STOP_ERROR runId={} error={}", message.runId(), error.getMessage());
                            send(outbound, ServerMessage.error(message.requestId(), message.runId(),
                                    error.getMessage()));
                        },
                        () -> {
                            log.info("AUDIT_WS_STOP_DONE runId={}", message.runId());
                            send(outbound, ServerMessage.stopped(message.requestId(), message.runId()));
                        }
                );
    }

    private void interrupt(ClientMessage message, Sinks.Many<String> outbound) {
        if (message.runId() == null || message.runId().isBlank()) {
            send(outbound, ServerMessage.error(message.requestId(), null, "interrupt 需要 runId"));
            return;
        }
        // interrupt 内部会查持久化上下文（JDBC），移到 boundedElastic。
        // 同 stop()：fromRunnable 空完成必须用 complete 回调发 interrupted
        Mono.fromRunnable(() -> agentService.interrupt(message.runId(), message.guidance()))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        null,
                        error -> send(outbound, ServerMessage.error(message.requestId(), message.runId(),
                                error.getMessage())),
                        () -> send(outbound, ServerMessage.interrupted(message.requestId(), message.runId()))
                );
    }

    /**
     * 连接关闭清理：
     * 1. 取消本连接的全部订阅；
     * 2. 对本连接订阅过的会话，若仍处于 RUNNING 且注册表中该 runId 已无其他活跃订阅，
     *    则调用 stop() 停止会话，避免断线后任务在后台空转。
     * 清理路径不允许抛错：任何异常只记 warn。
     */
    private void cleanupConnection(String connectionId) {
        Set<String> subscribedRunIds = new HashSet<>();
        subscriptions.entrySet().removeIf(entry -> {
            boolean belongs = entry.getKey().startsWith(connectionId + ":");
            if (belongs) {
                entry.getValue().dispose();
                String runId = entry.getKey().substring(connectionId.length() + 1);
                if (!runId.isEmpty()) {
                    subscribedRunIds.add(runId);
                }
            }
            return belongs;
        });

        for (String runId : subscribedRunIds) {
            try {
                if (hasOtherSubscriber(runId)) {
                    continue;
                }
                AgentSession session = agentSessionRegistry.getOrNull(runId);
                if (session != null && session.getStatus() == AgentSession.Status.RUNNING) {
                    session.stop();
                }
            } catch (Exception e) {
                log.warn("AUDIT_WS_CLEANUP_FAILED connectionId={} runId={} error={}",
                        connectionId, runId, e.getMessage());
            }
        }
    }

    /**
     * 判断某 runId 是否还存在其他连接的活跃订阅（本连接的条目已在上一步被移除）。
     */
    private boolean hasOtherSubscriber(String runId) {
        String suffix = ":" + runId;
        return subscriptions.keySet().stream().anyMatch(key -> key.endsWith(suffix));
    }

    private void send(Sinks.Many<String> outbound, ServerMessage message) {
        try {
            String json = objectMapper.writeValueAsString(message);
            Sinks.EmitResult result = outbound.tryEmitNext(json);
            if (result.isFailure()) {
                // 诊断：任何静默丢消息都要暴露（stopped/done 丢失曾导致测试挂死）
                log.warn("AUDIT_WS_SEND_FAILED type={} runId={} result={}", message.type(), message.runId(), result);
            }
        } catch (JsonProcessingException e) {
            outbound.tryEmitNext("{\"type\":\"error\",\"message\":\"消息序列化失败\"}");
        }
    }
}
