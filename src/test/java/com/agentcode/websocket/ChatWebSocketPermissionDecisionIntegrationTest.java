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

/** 验证 EDITED 替换参数与 REJECTED 终止执行两个分支。 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"agentcode.model.api-key=test"})
class ChatWebSocketPermissionDecisionIntegrationTest {

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
    void shouldRunEditedArgumentsInsteadOfOriginalCommand() throws Exception {
        Path workspace = temporaryDirectory();
        try (WebSocketTestSupport client = WebSocketTestSupport.connect(port)) {
            JsonNode permission =
                    requestPermission(client, workspace, "edited", "请执行 shell 命令");
            client.send(
                    json(
                            Map.of(
                                    "type", "permission_respond",
                                    "requestId", "edited-answer",
                                    "runId", permission.path("runId").asText(),
                                    "toolCallId", permission.path("toolCallId").asText(),
                                    "decision", "EDITED",
                                    "arguments", "{\"command\":\"mkdir edited_dir\"}")));

            List<String> messages =
                    client.collectUntil("\"type\":\"done\"", Duration.ofSeconds(30));
            assertThat(messages).anyMatch(message -> message.contains("RESPONSE_FINISHED"));
            assertThat(Files.isDirectory(workspace.resolve("edited_dir"))).isTrue();
            assertThat(Files.isDirectory(workspace.resolve("approved_dir"))).isFalse();
        } finally {
            deleteRecursively(workspace);
        }
    }

    @Test
    void shouldRejectShellCommandWithoutExecutingIt() throws Exception {
        Path workspace = temporaryDirectory();
        try (WebSocketTestSupport client = WebSocketTestSupport.connect(port)) {
            JsonNode permission =
                    requestPermission(client, workspace, "rejected", "请执行 shell 命令");
            client.send(
                    json(
                            Map.of(
                                    "type", "permission_respond",
                                    "requestId", "rejected-answer",
                                    "runId", permission.path("runId").asText(),
                                    "toolCallId", permission.path("toolCallId").asText(),
                                    "decision", "REJECTED",
                                    "feedback", "不允许创建目录")));

            List<String> messages =
                    client.collectUntil("\"type\":\"done\"", Duration.ofSeconds(30));
            assertThat(messages).anyMatch(message -> message.contains("RESPONSE_FINISHED"));
            assertThat(Files.exists(workspace.resolve("approved_dir"))).isFalse();
        } finally {
            deleteRecursively(workspace);
        }
    }

    private JsonNode requestPermission(
            WebSocketTestSupport client, Path workspace, String requestId, String goal)
            throws IOException {
        client.send(
                json(
                        Map.of(
                                "type", "start_session",
                                "requestId", requestId,
                                "goal", goal,
                                "workspace", workspace.toString())));
        List<String> messages =
                client.collectUntil(
                        "\"type\":\"permission_requested\"", Duration.ofSeconds(30));
        for (String message : messages) {
            JsonNode node = JSON.readTree(message);
            if ("permission_requested".equals(node.path("type").asText())) {
                return node;
            }
        }
        throw new IllegalStateException("没有收到 permission_requested");
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
            return Files.createTempDirectory("agentcode-ws-decision");
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
