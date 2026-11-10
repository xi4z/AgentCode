package com.agentcode.websocket;

import com.agentcode.testsupport.ScriptedModel;
import com.agentcode.testsupport.WebSocketTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.model.Model;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证同一个 runId 的连续两轮消息共享会话历史。 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"agentcode.model.api-key=test"})
class ChatWebSocketMultiTurnIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path RUNTIME_DIR = temporaryDirectory();

    @LocalServerPort
    int port;

    @Autowired
    Model model;

    @TestConfiguration
    static class ScriptedModelConfig {
        @Bean
        @Primary
        Model scriptedModel() {
            return new ScriptedModel();
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("agentcode.session.runtime-dir", () -> RUNTIME_DIR.toString());
    }

    @Test
    void shouldKeepMultiTurnHistoryAcrossWebSocketMessages() throws Exception {
        Path workspace = temporaryDirectory();
        try (WebSocketTestSupport client = WebSocketTestSupport.connect(port)) {
            client.send(
                    json(
                            Map.of(
                                    "type", "start_session",
                                    "requestId", "start-1",
                                    "goal", "第一轮",
                                    "workspace", workspace.toString())));

            List<String> first = client.collectUntil("\"type\":\"done\"", Duration.ofSeconds(30));
            assertThat(first)
                    .anyMatch(message -> message.contains("\"type\":\"session_started\""))
                    .anyMatch(message -> message.contains("RESPONSE_FINISHED"))
                    .anyMatch(message -> message.contains("\"type\":\"done\""));

            String runId = runId(first);
            client.send(
                    json(
                            Map.of(
                                    "type", "chat",
                                    "requestId", "chat-2",
                                    "runId", runId,
                                    "content", "第二轮")));

            List<String> second = client.collectUntil("\"type\":\"done\"", Duration.ofSeconds(30));
            assertThat(second)
                    .anyMatch(message -> message.contains("RESPONSE_FINISHED"))
                    .anyMatch(message -> message.contains("\"type\":\"done\""));

            assertThat(((ScriptedModel) model).userInputs()).contains("第一轮", "第二轮");
        } finally {
            deleteRecursively(workspace);
        }
    }

    private static String runId(List<String> messages) throws IOException {
        for (String message : messages) {
            JsonNode node = JSON.readTree(message);
            if ("session_started".equals(node.path("type").asText())) {
                return node.path("runId").asText();
            }
        }
        throw new IllegalStateException("没有收到 session_started");
    }

    private static String json(Map<String, Object> payload) {
        try {
            return JSON.writeValueAsString(payload);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private static Path temporaryDirectory() {
        try {
            return Files.createTempDirectory("agentcode-ws-multi");
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private static void deleteRecursively(Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (var stream = Files.walk(path)) {
            stream.sorted((left, right) -> right.compareTo(left))
                    .forEach(
                            item -> {
                                try {
                                    Files.deleteIfExists(item);
                                } catch (IOException ignored) {
                                    // 临时目录清理失败不影响断言
                                }
                            });
        } catch (IOException ignored) {
            // 临时目录清理失败不影响断言
        }
    }
}
