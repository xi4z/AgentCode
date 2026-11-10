package com.agentcode.websocket;

import com.agentcode.testsupport.ScriptedModel;
import com.agentcode.testsupport.WebSocketTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.model.Model;
import org.junit.jupiter.api.Test;
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

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"agentcode.model.api-key=test"})
class ChatWebSocketReconnectIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path RUNTIME_DIR = temporaryDirectory();

    @LocalServerPort
    int port;

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
    void shouldReplayDurableEventsAfterReconnect() throws Exception {
        Path workspace = temporaryDirectory();
        String runId;
        try (WebSocketTestSupport first = WebSocketTestSupport.connect(port)) {
            first.send(
                    json(
                            Map.of(
                                    "type", "start_session",
                                    "requestId", "reconnect-start",
                                    "goal", "断线重连",
                                    "workspace", workspace.toString())));
            List<String> started =
                    first.collectUntil("\"type\":\"done\"", Duration.ofSeconds(30));
            runId = runId(started);
            assertThat(started).anyMatch(message -> message.contains("RESPONSE_FINISHED"));

            first.send(
                    json(
                            Map.of(
                                    "type", "chat",
                                    "requestId", "reconnect-second",
                                    "runId", runId,
                                    "content", "第二轮")));
            List<String> secondTurn =
                    first.collectUntil("\"type\":\"done\"", Duration.ofSeconds(30));
            assertThat(secondTurn).anyMatch(message -> message.contains("RESPONSE_FINISHED"));
        }

        try (WebSocketTestSupport second = WebSocketTestSupport.connect(port)) {
            second.send(
                    json(
                            Map.of(
                                    "type", "attach",
                                    "requestId", "reconnect-attach",
                                    "runId", runId,
                                    "afterSeq", 0)));
            List<String> replayed =
                    second.collectUntil("\"type\":\"done\"", Duration.ofSeconds(30));
            assertThat(replayed)
                    .anyMatch(message -> message.contains("TURN_STARTED"))
                    .anyMatch(message -> message.contains("RESPONSE_FINISHED"))
                    .anyMatch(message -> message.contains("\"type\":\"done\""));
            assertThat(
                            replayed.stream()
                                    .filter(message -> message.contains("RESPONSE_FINISHED"))
                                    .count())
                    .isGreaterThanOrEqualTo(2);
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
            return Files.createTempDirectory("agentcode-ws-reconnect");
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
