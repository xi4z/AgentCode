package com.agentcode.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.ai.dashscope.api-key=test",
                "spring.ai.dashscope.agent.api-key=test",
                "spring.ai.openai.api-key=test"
        })
class ChatWebSocketMultiTurnIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    ObjectMapper objectMapper;

    @TestConfiguration
    static class MockChatModelConfig {

        @Bean
        @Primary
        ChatModel mockChatModel() {
            return new ChatModel() {
                private final AtomicInteger calls = new AtomicInteger();

                @Override
                public ChatResponse call(Prompt prompt) {
                    return response();
                }

                @Override
                public Flux<ChatResponse> stream(Prompt prompt) {
                    return Flux.just(response());
                }

                private ChatResponse response() {
                    int call = calls.incrementAndGet();
                    return new ChatResponse(List.of(
                            new Generation(new AssistantMessage("第 " + call + " 轮回复"))
                    ));
                }
            };
        }
    }

    @Test
    void shouldCompleteMultiTurnConversationOverWebSocket() throws Exception {
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

            // 第一轮：创建会话
            session.send(Mono.just(session.textMessage(
                    "{\"type\":\"start_session\",\"requestId\":\"r1\",\"goal\":\"你好\",\"workspace\":\"/tmp\"}"
            ))).block(Duration.ofSeconds(5));

            List<String> firstRound = collectUntilDone(inbound, "r1");
            String runId = extractRunId(firstRound);
            assertThat(firstRound)
                    .anyMatch(s -> s.contains("\"type\":\"session_started\""))
                    .anyMatch(s -> s.contains("\"RESPONSE_FINISHED\""))
                    .anyMatch(s -> s.contains("\"type\":\"done\""));

            // 第二轮：基于同一 runId 继续对话
            String chat = "{\"type\":\"chat\",\"requestId\":\"r2\",\"runId\":\"" + runId + "\",\"content\":\"继续\"}";
            session.send(Mono.just(session.textMessage(chat))).block(Duration.ofSeconds(5));

            List<String> secondRound = collectUntilDone(inbound, "r2");
            assertThat(secondRound)
                    .anyMatch(s -> s.contains("\"RESPONSE_FINISHED\""))
                    .anyMatch(s -> s.contains("\"type\":\"done\""));

            assertThat(runId).isNotBlank();
        } finally {
            WebSocketSession session = sessionRef.get();
            if (session != null) {
                session.close().block(Duration.ofSeconds(5));
            }
            connection.dispose();
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
        long deadline = System.currentTimeMillis() + 15_000;
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
}
