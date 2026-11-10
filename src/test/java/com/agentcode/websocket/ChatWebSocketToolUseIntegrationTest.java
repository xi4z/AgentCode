package com.agentcode.websocket;

import com.agentcode.testsupport.ScriptedModel;
import com.agentcode.testsupport.WebSocketTestSupport;
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
class ChatWebSocketToolUseIntegrationTest {

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
    void shouldExecuteReadFileThroughToolPipeline() throws Exception {
        Path workspace = temporaryDirectory();
        Files.writeString(workspace.resolve("hello.txt"), "AgentCode tool test");
        try (WebSocketTestSupport client = WebSocketTestSupport.connect(port)) {
            client.send(
                    json(
                            Map.of(
                                    "type", "start_session",
                                    "requestId", "tool-1",
                                    "goal", "请读取 hello.txt",
                                    "workspace", workspace.toString())));

            List<String> messages =
                    client.collectUntil("\"type\":\"done\"", Duration.ofSeconds(30));
            assertThat(messages)
                    .anyMatch(message -> message.contains("TOOL_STREAMING"))
                    .anyMatch(message -> message.contains("read_file"))
                    .anyMatch(message -> message.contains("TOOL_FINISHED"))
                    .anyMatch(message -> message.contains("RESPONSE_FINISHED"))
                    .anyMatch(message -> message.contains("\"type\":\"done\""));
        } finally {
            deleteRecursively(workspace);
        }
    }

    private static String json(Map<String, Object> payload) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(payload);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private static Path temporaryDirectory() {
        try {
            return Files.createTempDirectory("agentcode-ws-tool");
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
