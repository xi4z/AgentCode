package com.agentcode.agent;

import com.agentcode.context.AgentContext;
import com.agentcode.dto.AgentInterruptHandle;
import com.agentcode.dto.AgentStream;
import com.agentcode.factory.AgentHookBuilder;
import com.agentcode.factory.AgentSessionFactory;
import com.agentcode.factory.AgentToolBuilder;
import com.agentcode.factory.SessionBuildOptions;
import com.agentcode.properties.AgentCodeProperties;
import com.agentcode.session.AgentSession;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 APPROVE_ALL：用户选择当前会话放行后，后续相同命令不再触发审批，并且仍会正常执行。
 */
class AgentSessionApprovalAllTest {

    @Test
    void shouldCacheApprovedCommandInSession() throws Exception {
        Path workspace = Files.createTempDirectory("agent-session-approval-all-test");

        try {
            AgentContext context = AgentContext.builder()
                    .runId("approval-all-test-run")
                    .workspace(workspace.toString())
                    .goal("读取 /etc/hosts")
                    .build();
            MemorySaver saver = new MemorySaver();
            ShellCallMockChatModel chatModel = new ShellCallMockChatModel();
            AgentCodeProperties properties = new AgentCodeProperties();
            AgentSessionFactory factory = new AgentSessionFactory(
                    chatModel, saver, properties,
                    new AgentHookBuilder(chatModel, properties),
                    new AgentToolBuilder(chatModel));
            AgentSession session = factory.create(context, SessionBuildOptions.builder()
                    .approvalTools(List.of("shell"))
                    .build());

            // 第一次运行：shell 命令需要审批
            List<AgentStream> first = session.run("读取 /etc/hosts")
                    .collectList().block(Duration.ofSeconds(15));
            assertThat(first).isNotNull();
            assertThat(first)
                    .anyMatch(event -> event.status() == AgentStream.Status.PERMISSION_REQUESTED);

            // 用户选择 APPROVE_ALL
            AgentInterruptHandle handle = new AgentInterruptHandle(
                    "approval-all-test-run",
                    "call_shell",
                    "shell",
                    "{\"command\":\"cat /etc/hosts\"}",
                    "shell 需要审批",
                    AgentInterruptHandle.Decision.APPROVE_ALL,
                    null
            );
            List<AgentStream> resumed = session.handleAgentInterrupt(new AgentInterruptHandle[]{handle})
                    .collectList().block(Duration.ofSeconds(15));
            assertThat(resumed).isNotNull();
            assertThat(resumed)
                    .anyMatch(event -> event.status() == AgentStream.Status.TOOL_FINISHED);

            // 第二次运行相同命令：命中会话缓存，不再弹审批
            List<AgentStream> second = session.run("再次读取 /etc/hosts")
                    .collectList().block(Duration.ofSeconds(15));
            assertThat(second).isNotNull();
            assertThat(second)
                    .noneMatch(event -> event.status() == AgentStream.Status.PERMISSION_REQUESTED)
                    .anyMatch(event -> event.status() == AgentStream.Status.TOOL_FINISHED);
        } finally {
            deleteRecursively(workspace);
        }
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
                    // 忽略清理失败
                }
            });
        }
    }

    static class ShellCallMockChatModel implements ChatModel {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public ChatResponse call(Prompt prompt) {
            int call = calls.incrementAndGet();
            AssistantMessage message;
            if (call % 2 == 1) {
                message = AssistantMessage.builder()
                        .content("")
                        .toolCalls(List.of(new AssistantMessage.ToolCall(
                                "call_shell", "function", "shell",
                                "{\"command\":\"cat /etc/hosts\"}")))
                        .build();
            } else {
                message = new AssistantMessage("done " + call);
            }
            return new ChatResponse(List.of(new Generation(message)));
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.just(call(prompt));
        }
    }
}
