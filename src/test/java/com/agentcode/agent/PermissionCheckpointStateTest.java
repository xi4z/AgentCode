package com.agentcode.agent;

import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.action.InterruptionMetadata;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.extension.tools.filesystem.FileSystemTools;
import com.alibaba.cloud.ai.graph.agent.hook.hip.HumanInTheLoopHook;
import com.alibaba.cloud.ai.graph.agent.hook.hip.ToolConfig;
import com.alibaba.cloud.ai.graph.agent.hook.shelltool.ShellToolAgentHook;
import com.alibaba.cloud.ai.graph.agent.tools.GlobSearchTool;
import com.alibaba.cloud.ai.graph.agent.tools.GrepSearchTool;
import com.alibaba.cloud.ai.graph.agent.tools.ShellTool2;
import com.alibaba.cloud.ai.graph.checkpoint.Checkpoint;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 检查 HumanInTheLoop 权限审批中断发生后，MemorySaver 中 checkpoint state 的内容。
 *
 * 设计：直接驱动 ReactAgent + HumanInTheLoopHook，mock ChatModel 只发一次 shell 工具调用，
 * 流在审批中断处暂停；随后读取 saver.get(config).get().getState()，确认 state 保存了哪些数据。
 */
class PermissionCheckpointStateTest {

    @Test
    void shouldExposeCheckpointStateAfterPermissionInterruption() throws Exception {
        Path workspace = Files.createTempDirectory("permission-checkpoint-state-test");
        Files.writeString(workspace.resolve("hello.txt"), "hello");

        try {
            MemorySaver saver = new MemorySaver();
            ReactAgent agent = ReactAgent.builder()
                    .name("permission_checkpoint_agent")
                    .model(new ShellMockChatModel(workspace.toString()))
                    .saver(saver)
                    .tools(List.of(
                            GrepSearchTool.builder(workspace.toString()).build(),
                            GlobSearchTool.builder(workspace.toString()).build()
                    ))
                    .methodTools(FileSystemTools.builder()
                            .rootDir(workspace.toString())
                            .maxFileSizeMb(10)
                            .build())
                    .hooks(List.of(
                            ShellToolAgentHook.builder()
                                    .shellTool2(ShellTool2.builder(workspace.toString()).build())
                                    .shellToolName("shell")
                                    .build(),
                            HumanInTheLoopHook.builder()
                                    .approvalOn("shell", ToolConfig.builder()
                                            .description("shell 命令需要人工审批")
                                            .build())
                                    .build()
                    ))
                    .build();

            RunnableConfig config = RunnableConfig.builder()
                    .threadId("permission-checkpoint-state-run")
                    .build();

            InterruptionMetadata interruption = agent.stream("请执行 echo hello", config)
                    .filter(node -> node instanceof InterruptionMetadata)
                    .cast(InterruptionMetadata.class)
                    .next()
                    .block(Duration.ofSeconds(15));

            assertThat(interruption).as("应当触发 HumanInTheLoop 审批中断").isNotNull();
            assertThat(interruption.toolFeedbacks())
                    .anyMatch(feedback -> "shell".equals(feedback.getName()));

            Checkpoint checkpoint = saver.get(config).orElseThrow();
            Map<String, Object> state = checkpoint.getState();

            // 打印出来便于人工检查/调试
            System.out.println("=== checkpoint state ===");
            state.forEach((key, value) ->
                    System.out.println(key + " -> " + value.getClass().getSimpleName() + " : " + abbreviate(value)));

            System.out.println("=== config.context ===");
            config.context().forEach((key, value) ->
                    System.out.println(key + " -> " + (value == null ? "null" : value.getClass().getSimpleName()) + " : " + abbreviate(value)));

            // 审批中断后，state 至少包含这三类数据
            assertThat(state).containsKeys("input", "messages", "_graph_execution_id_");
            assertThat(state.get("input")).isEqualTo("请执行 echo hello");
            assertThat(state.get("messages")).isInstanceOf(List.class);

            List<?> messages = (List<?>) state.get("messages");
            assertThat(messages)
                    .anyMatch(message -> message instanceof AssistantMessage assistant
                            && assistant.hasToolCalls()
                            && assistant.getToolCalls().stream()
                                    .anyMatch(tool -> "shell".equals(tool.name())));
        } finally {
            deleteRecursively(workspace);
        }
    }

    private static String abbreviate(Object value) {
        String text = String.valueOf(value);
        return text.length() <= 300 ? text : text.substring(0, 300) + "...";
    }

    private static void deleteRecursively(Path path) throws Exception {
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

    /**
     * 只发一次 shell 工具调用，之后不会继续调用，便于停在审批中断点。
     */
    static class ShellMockChatModel implements ChatModel {
        private final String workspace;
        private final AtomicInteger calls = new AtomicInteger();

        ShellMockChatModel(String workspace) {
            this.workspace = workspace;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            int call = calls.incrementAndGet();
            AssistantMessage message;
            if (call == 1) {
                message = AssistantMessage.builder()
                        .content("")
                        .toolCalls(List.of(new AssistantMessage.ToolCall(
                                "call_shell", "function", "shell",
                                "{\"command\":\"echo hello\"}")))
                        .build();
            } else {
                message = new AssistantMessage("done");
            }
            return new ChatResponse(List.of(new Generation(message)));
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.just(call(prompt));
        }
    }
}
