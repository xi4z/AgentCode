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

/** 验证 shell 调用挂起、前端批准后恢复执行。 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"agentcode.model.api-key=test"})
class ChatWebSocketPermissionApprovalIntegrationTest {

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
    void shouldPauseForShellApprovalAndResumeAfterApproval() throws Exception {
        Path workspace = temporaryDirectory();
        try (WebSocketTestSupport client = WebSocketTestSupport.connect(port)) {
            client.send(
                    json(
                            Map.of(
                                    "type", "start_session",
                                    "requestId", "perm-1",
                                    "goal", "请执行 shell 命令创建目录",
                                    "workspace", workspace.toString())));

            List<String> permissionMessages =
                    client.collectUntil(
                            "\"type\":\"permission_requested\"", Duration.ofSeconds(30));
            JsonNode permission = permissionMessage(permissionMessages);
            assertThat(permission.path("toolName").asText()).isEqualTo("shell");
            String toolCallId = permission.path("toolCallId").asText();
            assertThat(toolCallId).isNotBlank();
            assertThat(permission.path("arguments").asText()).contains("approved_dir");

            client.send(
                    json(
                            Map.of(
                                    "type", "permission_respond",
                                    "requestId", "perm-answer-1",
                                    "runId", permission.path("runId").asText(),
                                    "toolCallId", toolCallId,
                                    "decision", "APPROVED")));

            List<String> afterApproval =
                    client.collectUntil("\"type\":\"done\"", Duration.ofSeconds(30));
            assertThat(afterApproval)
                    .anyMatch(message -> message.contains("TOOL_FINISHED"))
                    .anyMatch(message -> message.contains("RESPONSE_FINISHED"))
                    .anyMatch(message -> message.contains("\"type\":\"done\""));
            assertThat(Files.isDirectory(workspace.resolve("approved_dir"))).isTrue();
        } finally {
            deleteRecursively(workspace);
        }
    }

    private static JsonNode permissionMessage(List<String> messages) throws IOException {
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
            return Files.createTempDirectory("agentcode-ws-permission");
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
