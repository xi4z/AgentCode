package com.agentcode.agent;

import com.agentcode.context.AgentContext;
import com.agentcode.dto.AgentInterruptHandle;
import com.agentcode.dto.AgentStream;
import com.agentcode.factory.AgentSessionFactory;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 会话审批生命周期：
 * 1) 需要审批的工具由 {@code agentcode.agent.approval-tools} 决定；
 * 2) 长时间无人答复的审批可以被放弃，会话不会永久卡在 INTERRUPTED。
 */
class AgentSessionApprovalLifecycleTest {

    @Test
    void shouldOnlyGateToolsListedInConfiguration() throws Exception {
        Path workspace = Files.createTempDirectory("agentcode-approval-tools-config");
        try {
            AgentCodeProperties properties = new AgentCodeProperties();
            // 只审批 edit_file：shell 调用应当直接执行，不再弹审批
            properties.getAgent().setApprovalTools(List.of("edit_file"));

            AgentSession session = new AgentSessionFactory(
                    new ShellCallModel(), new MemorySaver(), properties)
                    .create(context(workspace, "tools-config-run"));

            List<AgentStream> events = session.run("读取 /etc/hosts")
                    .collectList().block(Duration.ofSeconds(20));

            assertThat(events)
                    .noneMatch(event -> event.status() == AgentStream.Status.PERMISSION_REQUESTED);
            assertThat(events)
                    .anyMatch(event -> event.status() == AgentStream.Status.TOOL_FINISHED);
        } finally {
            deleteRecursively(workspace);
        }
    }

    @Test
    void shouldAbandonApprovalThatNobodyAnswers() throws Exception {
        Path workspace = Files.createTempDirectory("agentcode-approval-timeout");
        try {
            AgentCodeProperties properties = new AgentCodeProperties();
            properties.getAgent().setApprovalTools(List.of("shell"));

            AgentSession session = new AgentSessionFactory(
                    new ShellCallModel(), new MemorySaver(), properties)
                    .create(context(workspace, "timeout-run"));

            List<AgentStream> events = session.run("读取 /etc/hosts")
                    .collectList().block(Duration.ofSeconds(20));
            assertThat(events)
                    .anyMatch(event -> event.status() == AgentStream.Status.PERMISSION_REQUESTED);
            assertThat(session.getStatus()).isEqualTo(AgentSession.Status.INTERRUPTED);

            Thread.sleep(5);
            // 未超时的等待不应被放弃
            assertThat(session.abandonStaleApproval(Duration.ofHours(1))).isFalse();
            assertThat(session.getStatus()).isEqualTo(AgentSession.Status.INTERRUPTED);

            assertThat(session.abandonStaleApproval(Duration.ofMillis(1))).isTrue();
            assertThat(session.getStatus()).isEqualTo(AgentSession.Status.FREE);

            // 放弃之后再答复必须失败，不能把已停止的轮次救回来
            AgentInterruptHandle handle = new AgentInterruptHandle(
                    "timeout-run", "call_shell", "shell",
                    "{\"command\":\"cat /etc/hosts\"}", null,
                    AgentInterruptHandle.Decision.APPROVED, null);
            assertThatThrownBy(() -> session.handleAgentInterrupt(new AgentInterruptHandle[]{handle}))
                    .isInstanceOf(com.agentcode.exception.InterruptFailException.class);
        } finally {
            deleteRecursively(workspace);
        }
    }

    private AgentContext context(Path workspace, String runId) {
        return AgentContext.builder()
                .runId(runId)
                .workspace(workspace.toString())
                .goal("读取 /etc/hosts")
                .build();
    }

    /** 第一次调用要求执行 shell（绝对路径命令），之后返回最终回答 */
    static class ShellCallModel implements ChatModel {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public ChatResponse call(Prompt prompt) {
            int call = calls.incrementAndGet();
            AssistantMessage message = call % 2 == 1
                    ? AssistantMessage.builder()
                    .content("")
                    .toolCalls(List.of(new AssistantMessage.ToolCall(
                            "call_shell", "function", "shell",
                            "{\"command\":\"cat /etc/hosts\"}")))
                    .build()
                    : new AssistantMessage("done " + call);
            return new ChatResponse(List.of(new Generation(message)));
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.just(call(prompt));
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
}
