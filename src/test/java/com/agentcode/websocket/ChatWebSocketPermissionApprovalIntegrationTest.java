package com.agentcode.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 端到端验证 WebSocket 权限审批链路：
 * 模型请求 shell 工具 → 服务端发送 permission_requested → 客户端 APPROVED → Agent 恢复执行并完成。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.ai.dashscope.api-key=test",
                "spring.ai.dashscope.agent.api-key=test",
                "spring.ai.openai.api-key=test"
        })
class ChatWebSocketPermissionApprovalIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    ChatModel chatModel;

    @TestConfiguration
    static class PermissionMockChatModelConfig {

        @Bean
        @Primary
        ChatModel permissionMockChatModel() {
            return new PermissionMockChatModel();
        }
    }

    static class PermissionMockChatModel implements ChatModel {
        final AtomicInteger calls = new AtomicInteger();
        volatile boolean toolResultSeen = false;

        @Override
        public ChatResponse call(Prompt prompt) {
            return responseFor(prompt);
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            boolean hasToolResult = prompt.getInstructions().stream()
                    .anyMatch(message -> message instanceof ToolResponseMessage);
            if (hasToolResult) {
                toolResultSeen = true;
            }
            return Flux.just(responseFor(prompt));
        }

        private ChatResponse responseFor(Prompt prompt) {
            int call = calls.incrementAndGet();
            AssistantMessage message;
            if (call % 2 == 1) {
                // 使用 outside-cwd 命令，确保静态评估不会自动放行
                message = AssistantMessage.builder()
                        .content("")
                        .toolCalls(List.of(new AssistantMessage.ToolCall(
                                "call_shell", "function", "shell",
                                "{\"command\":\"cat /etc/hosts\"}")))
                        .build();
            } else {
                message = new AssistantMessage("已完成");
            }
            return new ChatResponse(List.of(new Generation(message)));
        }
    }

    @Test
    void shouldRequestPermissionAndResumeAfterApproval() throws Exception {
        Path workspace = Files.createTempDirectory("agentcode-ws-permission-test");

        ReactorNettyWebSocketClient client = new ReactorNettyWebSocketClient();
        URI uri = URI.create("ws://localhost:" + port + "/ws/chat");

        AtomicReference<WebSocketSession> sessionRef = new AtomicReference<>();
        BlockingQueue<String> inbound = new LinkedBlockingQueue<>();

        Disposable connection = client.execute(uri, session -> {
            sessionRef.set(session);
            return session.receive()
                    .map(WebSocketMessage::getPayloadAsText)
                    .doOnNext(inbound::offer)
                    .then();
        }).subscribe();

        try {
            awaitSession(sessionRef);
            WebSocketSession session = sessionRef.get();

            String start = "{\"type\":\"start_session\",\"requestId\":\"p1\","
                    + "\"goal\":\"请使用 shell 读取 /etc/hosts\","
                    + "\"workspace\":\"" + workspace + "\"}";
            session.send(Mono.just(session.textMessage(start))).block(Duration.ofSeconds(5));

            List<String> firstRound = collectUntilPermissionRequested(inbound);
            String runId = extractRunId(firstRound);
            String toolCallId = extractToolCallId(firstRound);

            assertThat(runId).isNotBlank();
            assertThat(toolCallId).isNotBlank();
            assertThat(firstRound)
                    .anyMatch(s -> s.contains("\"type\":\"permission_requested\""))
                    .anyMatch(s -> s.contains("shell"));
            assertThat(firstRound)
                    .noneMatch(s -> s.contains("\"type\":\"done\""));

            String respond = "{\"type\":\"permission_respond\",\"requestId\":\"p2\","
                    + "\"runId\":\"" + runId + "\","
                    + "\"toolCallId\":\"" + toolCallId + "\","
                    + "\"toolName\":\"shell\","
                    + "\"decision\":\"APPROVED\"}";
            session.send(Mono.just(session.textMessage(respond))).block(Duration.ofSeconds(5));

            List<String> resumed = collectUntilDone(inbound);
            assertThat(resumed)
                    .anyMatch(s -> s.contains("TOOL_FINISHED"))
                    .anyMatch(s -> s.contains("RESPONSE_FINISHED"))
                    .anyMatch(s -> s.contains("\"type\":\"done\""));

            assertThat(toolResultSeen()).as("批准后模型应收到工具执行结果").isTrue();
            assertThat(calls()).as("应发生一次工具调用和一次最终回复").isEqualTo(2);
        } finally {
            WebSocketSession session = sessionRef.get();
            if (session != null) {
                session.close().block(Duration.ofSeconds(5));
            }
            connection.dispose();
            deleteRecursively(workspace);
        }
    }

    private boolean toolResultSeen() {
        return ((PermissionMockChatModel) chatModel).toolResultSeen;
    }

    private int calls() {
        return ((PermissionMockChatModel) chatModel).calls.get();
    }

    private void awaitSession(AtomicReference<WebSocketSession> sessionRef) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (sessionRef.get() == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertThat(sessionRef.get()).isNotNull();
    }

    private List<String> collectUntilPermissionRequested(BlockingQueue<String> inbound) throws Exception {
        List<String> messages = new ArrayList<>();
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            String message = inbound.poll(1, TimeUnit.SECONDS);
            if (message == null) {
                continue;
            }
            messages.add(message);
            if (message.contains("\"type\":\"permission_requested\"")) {
                return messages;
            }
        }
        throw new AssertionError("未在超时时间内收到 permission_requested，已收到: " + messages);
    }

    private List<String> collectUntilDone(BlockingQueue<String> inbound) throws Exception {
        List<String> messages = new ArrayList<>();
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            String message = inbound.poll(1, TimeUnit.SECONDS);
            if (message == null) {
                continue;
            }
            messages.add(message);
            if (message.contains("\"type\":\"done\"")) {
                return messages;
            }
        }
        throw new AssertionError("未在超时时间内收到 done，已收到: " + messages);
    }

    private String extractRunId(List<String> messages) throws Exception {
        for (String message : messages) {
            JsonNode node = objectMapper.readTree(message);
            String runId = node.path("runId").asText(null);
            if (runId != null && !runId.isBlank()) {
                return runId;
            }
        }
        throw new IllegalStateException("没有找到 runId");
    }

    private String extractToolCallId(List<String> messages) throws Exception {
        for (String message : messages) {
            JsonNode node = objectMapper.readTree(message);
            if ("permission_requested".equals(node.path("type").asText())) {
                return node.path("toolCallId").asText();
            }
        }
        throw new IllegalStateException("没有找到 toolCallId");
    }

    private void deleteRecursively(Path path) throws Exception {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (var stream = Files.walk(path)) {
            stream.sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                    // 忽略清理失败
                }
            });
        }
    }
}
