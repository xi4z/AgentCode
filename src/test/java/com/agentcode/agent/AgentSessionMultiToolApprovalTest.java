package com.agentcode.agent;

import com.agentcode.context.AgentContext;
import com.agentcode.dto.AgentInterruptHandle;
import com.agentcode.dto.AgentStream;
import com.agentcode.factory.AgentSessionFactory;
import com.agentcode.factory.SessionBuildOptions;
import com.agentcode.exception.InterruptFailException;
import com.agentcode.session.AgentSession;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 回归测试：一轮 interruption 同时挂起多个工具时，审批必须能恢复。
 *
 * 旧实现里客户端只能逐个 toolCallId 提交 permission_respond，第一个决定就会把会话恢复成
 * RUNNING，第二个决定直接抛 InterruptFailException，多工具审批卡死。
 * 现在服务端按轮缓存决定，答复齐了才恢复；也支持一次批量提交。
 */
class AgentSessionMultiToolApprovalTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void shouldResumeOnlyAfterEveryPendingToolIsAnswered() throws Exception {
        Path workspace = Files.createTempDirectory("agentcode-multi-approval");
        try {
            AgentContext context = AgentContext.builder()
                    .runId("multi-approval-test")
                    .workspace(workspace.toString())
                    .goal("并行读取两个系统文件")
                    .build();
            AgentSessionFactory factory = new AgentSessionFactory(
                    new TwoShellCallsThenDoneModel(), new MemorySaver(), null);
            AgentSession session = factory.create(context, SessionBuildOptions.builder()
                    .approvalTools(List.of("shell"))
                    .build());

            List<AgentStream> first = session.run("并行读取两个系统文件")
                    .collectList().block(Duration.ofSeconds(20));
            List<String> pendingIds = permissionToolCallIds(first);
            assertThat(pendingIds)
                    .as("一次中断应同时挂起两个 shell 审批")
                    .hasSize(2);

            // 先只答复第一个：不能恢复执行，只回一个 PERMISSION_PENDING
            Flux<AgentStream> partial = session.handleAgentInterrupt(
                    new AgentInterruptHandle[]{handle(context.getRunId(), pendingIds.get(0), "shell",
                            AgentInterruptHandle.Decision.APPROVED)});
            List<AgentStream> partialEvents = partial.collectList().block(Duration.ofSeconds(10));
            assertThat(partialEvents).hasSize(1);
            assertThat(partialEvents.get(0).status()).isEqualTo(AgentStream.Status.PERMISSION_PENDING);
            assertThat(partialEvents.get(0).content()).contains(pendingIds.get(1));

            // 答复第二个：本轮才真正恢复
            List<AgentStream> resumed = session.handleAgentInterrupt(
                    new AgentInterruptHandle[]{handle(context.getRunId(), pendingIds.get(1), "shell",
                            AgentInterruptHandle.Decision.APPROVED)})
                    .collectList().block(Duration.ofSeconds(20));
            assertThat(resumed)
                    .anyMatch(event -> event.status() == AgentStream.Status.TOOL_FINISHED);
            assertThat(resumed)
                    .noneMatch(event -> event.status() == AgentStream.Status.PERMISSION_PENDING);

            // 第二轮：待审批状态已清理干净，批量提交两个决定即可恢复
            List<AgentStream> secondRound = session.run("再来一次")
                    .collectList().block(Duration.ofSeconds(20));
            List<String> nextPending = permissionToolCallIds(secondRound);
            assertThat(nextPending).hasSize(2);
            assertThat(nextPending).doesNotContainAnyElementsOf(pendingIds);

            List<AgentStream> batched = session.handleAgentInterrupt(new AgentInterruptHandle[]{
                            handle(context.getRunId(), nextPending.get(0), "shell",
                                    AgentInterruptHandle.Decision.APPROVED),
                            handle(context.getRunId(), nextPending.get(1), "shell",
                                    AgentInterruptHandle.Decision.REJECTED)})
                    .collectList().block(Duration.ofSeconds(20));
            assertThat(batched)
                    .noneMatch(event -> event.status() == AgentStream.Status.PERMISSION_PENDING)
                    .anyMatch(event -> event.status() == AgentStream.Status.TOOL_FINISHED
                            || event.status() == AgentStream.Status.RESPONSE_FINISHED);
        } finally {
            deleteRecursively(workspace);
        }
    }

    private AgentInterruptHandle handle(String runId, String toolCallId, String toolName,
                                        AgentInterruptHandle.Decision decision) {
        return new AgentInterruptHandle(runId, toolCallId, toolName, null, null, decision, null);
    }

    /**
     * 等审批期间必须可以 stop()：旧实现会因为没有 runningTask 直接抛 TaskNotFoundException，
     * 用户只能看着会话卡在 INTERRUPTED。
     */
    @Test
    void shouldStopWhileWaitingForApprovals() throws Exception {
        Path workspace = Files.createTempDirectory("agentcode-approval-stop");
        try {
            AgentContext context = AgentContext.builder()
                    .runId("approval-stop-test")
                    .workspace(workspace.toString())
                    .goal("读取系统文件")
                    .build();
            AgentSessionFactory factory = new AgentSessionFactory(
                    new TwoShellCallsThenDoneModel(), new MemorySaver(), null);
            AgentSession session = factory.create(context, SessionBuildOptions.builder()
                    .approvalTools(List.of("shell"))
                    .build());

            List<AgentStream> events = session.run("读取系统文件")
                    .collectList().block(Duration.ofSeconds(20));
            List<String> pendingIds = permissionToolCallIds(events);
            assertThat(session.getStatus()).isEqualTo(AgentSession.Status.INTERRUPTED);

            // 只答复一项后停止：会话回到 FREE，待审批上下文作废
            session.handleAgentInterrupt(new AgentInterruptHandle[]{
                    handle(context.getRunId(), pendingIds.get(0), "shell", AgentInterruptHandle.Decision.APPROVED)})
                    .collectList().block(Duration.ofSeconds(10));
            assertThatCode(session::stop).doesNotThrowAnyException();
            assertThat(session.getStatus()).isEqualTo(AgentSession.Status.FREE);
            assertThatThrownBy(() -> session.handleAgentInterrupt(new AgentInterruptHandle[]{
                    handle(context.getRunId(), pendingIds.get(1), "shell", AgentInterruptHandle.Decision.APPROVED)}))
                    .isInstanceOf(InterruptFailException.class);
        } finally {
            deleteRecursively(workspace);
        }
    }

    private List<String> permissionToolCallIds(List<AgentStream> events) throws Exception {
        assertThat(events).isNotNull();
        List<String> ids = new ArrayList<>();
        AgentStream requested = events.stream()
                .filter(event -> event.status() == AgentStream.Status.PERMISSION_REQUESTED)
                .findFirst()
                .orElseThrow(() -> new AssertionError("未触发权限审批: " + statuses(events)));
        JsonNode array = MAPPER.readTree(requested.content());
        for (JsonNode node : array) {
            ids.add(node.path("toolCallId").asText());
        }
        return ids;
    }

    private List<AgentStream.Status> statuses(List<AgentStream> events) {
        return events == null ? List.of() : events.stream().map(AgentStream::status).toList();
    }

    /**
     * 第 1、3 次模型调用返回两个 shell 工具调用（绝对路径命令，必定需要审批），其余返回最终回答。
     */
    static class TwoShellCallsThenDoneModel implements ChatModel {
        private final Deque<ChatResponse> scripted = new ArrayDeque<>();

        TwoShellCallsThenDoneModel() {
            scripted.add(toolCallResponse("call_a_1", "cat /etc/hosts", "call_b_1", "cat /etc/hostname"));
            scripted.add(textResponse("round-1-done"));
            scripted.add(toolCallResponse("call_a_2", "cat /etc/hosts", "call_b_2", "cat /etc/hostname"));
            scripted.add(textResponse("round-2-done"));
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            ChatResponse next = scripted.poll();
            return next != null ? next : textResponse("fallback-done");
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.just(call(prompt));
        }

        private static ChatResponse toolCallResponse(String idA, String cmdA, String idB, String cmdB) {
            return new ChatResponse(List.of(new Generation(AssistantMessage.builder()
                    .content("")
                    .toolCalls(List.of(
                            new AssistantMessage.ToolCall(idA, "function", "shell",
                                    "{\"command\":\"" + cmdA + "\"}"),
                            new AssistantMessage.ToolCall(idB, "function", "shell",
                                    "{\"command\":\"" + cmdB + "\"}")))
                    .build())));
        }

        private static ChatResponse textResponse(String text) {
            return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
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
