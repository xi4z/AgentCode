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
 * 深度测试：WebSocket 多轮会话中，goal 需要使用真实工具（list_files / read_file）。
 *
 * 使用 mock ChatModel 模拟模型先发起工具调用，再由 Agent 执行真实文件系统工具后继续回复，
 * 以验证工具调用事件能通过 WebSocket 推送，并且多轮对话仍然基于同一 runId 保留上下文。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.ai.dashscope.api-key=test",
                "spring.ai.dashscope.agent.api-key=test",
                "spring.ai.openai.api-key=test"
        })
class ChatWebSocketToolUseIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    ChatModel chatModel;

    @TestConfiguration
    static class ToolAwareChatModelConfig {

        @Bean
        @Primary
        ChatModel toolAwareChatModel() {
            return new ToolAwareChatModel();
        }
    }

    static class ToolAwareChatModel implements ChatModel {
        final AtomicInteger calls = new AtomicInteger();
        volatile boolean toolResultSeen = false;
        volatile String workspace;

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
            switch (call) {
                case 1 -> message = toolCall("call_list",
                        "list_files",
                        "{\"path\":\"" + workspace + "\"}");
                case 2 -> message = new AssistantMessage("目录中找到了 hello.txt");
                case 3 -> message = toolCall("call_read",
                        "read_file",
                        "{\"filePath\":\"" + workspace + "/hello.txt\"}");
                default -> message = new AssistantMessage("文件内容是 AgentCode tool test");
            }
            return new ChatResponse(List.of(new Generation(message)));
        }

        private AssistantMessage toolCall(String id, String name, String arguments) {
            return AssistantMessage.builder()
                    .content("")
                    .toolCalls(List.of(new AssistantMessage.ToolCall(id, "function", name, arguments)))
                    .build();
        }
    }

    @Test
    void shouldRunToolUseGoalsAcrossWebSocketTurns() throws Exception {
        Path workspace = Files.createTempDirectory("agentcode-ws-tool-test");
        Files.writeString(workspace.resolve("hello.txt"), "AgentCode tool test");

        ToolAwareChatModel mock = (ToolAwareChatModel) chatModel;
        mock.workspace = workspace.toString();

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

            // 第一轮：goal 需要使用 list_files 工具
            String start = "{\"type\":\"start_session\",\"requestId\":\"t1\","
                    + "\"goal\":\"请使用 list_files 工具查看工作目录，并告诉我里面有哪些文件\","
                    + "\"workspace\":\"" + workspace + "\"}";
            session.send(Mono.just(session.textMessage(start))).block(Duration.ofSeconds(5));

            List<String> firstRound = collectUntilDone(inbound, "t1");
            String runId = extractRunId(firstRound);
            assertThat(runId).isNotBlank();
            assertThat(firstRound)
                    .anyMatch(s -> s.contains("\"type\":\"session_started\""))
                    .anyMatch(s -> s.contains("TOOL_STREAMING"))
                    .anyMatch(s -> s.contains("TOOL_FINISHED"))
                    .anyMatch(s -> s.contains("RESPONSE_FINISHED"))
                    .anyMatch(s -> s.contains("\"type\":\"done\""));

            // 第二轮：继续使用同一 runId，goal 需要 read_file 工具
            String chat = "{\"type\":\"chat\",\"requestId\":\"t2\",\"runId\":\"" + runId
                    + "\",\"content\":\"请使用 read_file 工具读取 hello.txt 的内容\"}";
            session.send(Mono.just(session.textMessage(chat))).block(Duration.ofSeconds(5));

            List<String> secondRound = collectUntilDone(inbound, "t2");
            assertThat(secondRound)
                    .anyMatch(s -> s.contains("TOOL_STREAMING"))
                    .anyMatch(s -> s.contains("TOOL_FINISHED"))
                    .anyMatch(s -> s.contains("RESPONSE_FINISHED"))
                    .anyMatch(s -> s.contains("\"type\":\"done\""));

            // 证明 Agent 确实执行了工具：模型至少见过一次 ToolResponseMessage
            assertThat(mock.toolResultSeen).as("模型应收到工具执行结果").isTrue();
            assertThat(mock.calls.get()).as("两轮工具调用至少应有 4 次模型请求").isGreaterThanOrEqualTo(4);
        } finally {
            WebSocketSession session = sessionRef.get();
            if (session != null) {
                session.close().block(Duration.ofSeconds(5));
            }
            connection.dispose();
            deleteRecursively(workspace);
        }
    }

    private void awaitSession(AtomicReference<WebSocketSession> sessionRef) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (sessionRef.get() == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertThat(sessionRef.get()).isNotNull();
    }

    private List<String> collectUntilDone(BlockingQueue<String> inbound, String requestId) throws Exception {
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
        throw new AssertionError("未在超时时间内收到 done，requestId=" + requestId + "，已收到: " + messages);
    }

    private String extractRunId(List<String> messages) throws Exception {
        for (String message : messages) {
            JsonNode node = objectMapper.readTree(message);
            if ("session_started".equals(node.path("type").asText())) {
                return node.path("runId").asText();
            }
        }
        throw new IllegalStateException("没有收到 session_started 消息");
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
                    // 测试临时目录清理失败不影响断言结果
                }
            });
        }
    }
}
