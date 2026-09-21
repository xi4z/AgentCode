package com.agentcode.agent;

import com.agentcode.context.AgentContext;
import com.agentcode.dto.AgentInterruptHandle;
import com.agentcode.dto.AgentStream;
import com.agentcode.factory.AgentSessionFactory;
import com.agentcode.factory.SessionBuildOptions;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 一条 assistant 消息里同时产生多个待审批工具调用时的审批与恢复行为。
 *
 * 覆盖 handleToolApproval 的"没批完就打回"分支：部分答复不恢复执行，
 * 只把剩余的待审批项重新推给前端；凑齐后才 resume。
 */
class AgentSessionMultiPendingApprovalTest {

    private static final String RUN_ID = "multi-pending-run";

    @Test
    void shouldResumeOnlyAfterEveryPendingApprovalAnswered() throws Exception {
        Path workspace = Files.createTempDirectory("agent-session-multi-pending-test");

        try {
            AgentSession session = newSession(workspace);

            // 第一次运行：一条消息里两个 shell 调用，一次中断应带两个待审批工具
            List<AgentStream> first = session.run("读两个系统文件")
                    .collectList().block(Duration.ofSeconds(15));
            assertThat(first).isNotNull();
            assertThat(pendingToolIds(first))
                    .containsExactlyInAnyOrder("call_shell_1", "call_shell_2");
            assertThat(first).noneMatch(event -> event.status() == AgentStream.Status.TOOL_FINISHED);

            // 只答其中一个：打回，重推剩下那个，本轮不恢复执行
            List<AgentStream> partial = answer(session, "call_shell_1", "shell",
                    "{\"command\":\"cat /etc/hosts\"}");
            assertThat(partial).isNotNull();
            assertThat(pendingToolIds(partial)).containsExactly("call_shell_2");
            assertThat(partial).noneMatch(event -> event.status() == AgentStream.Status.TOOL_FINISHED);

            // 重复答复已处理过的 id：跳过，不重复累积，也不影响剩余项
            List<AgentStream> repeated = answer(session, "call_shell_1", "shell",
                    "{\"command\":\"cat /etc/hosts\"}");
            assertThat(repeated).isNotNull();
            assertThat(pendingToolIds(repeated)).containsExactly("call_shell_2");

            // 补齐最后一个：恢复执行，两个工具都跑完
            List<AgentStream> resumed = answer(session, "call_shell_2", "shell",
                    "{\"command\":\"head -n 1 /etc/hostname\"}");
            assertThat(resumed).isNotNull();
            assertThat(resumed).anyMatch(event -> event.status() == AgentStream.Status.TOOL_FINISHED);
            assertThat(resumed).noneMatch(event -> event.status() == AgentStream.Status.PERMISSION_REQUESTED);
        } finally {
            deleteRecursively(workspace);
        }
    }

    private static AgentSession newSession(Path workspace) {
        AgentContext context = AgentContext.builder()
                .runId(RUN_ID)
                .workspace(workspace.toString())
                .goal("读两个系统文件")
                .build();
        return new AgentSessionFactory(new TwoShellCallsMockChatModel(), new MemorySaver(), null)
                .create(context, SessionBuildOptions.builder()
                        .approvalTools(List.of("shell"))
                        .build());
    }

    /** 最后一次 PERMISSION_REQUESTED 事件里携带的待审批工具 id */
    private static List<String> pendingToolIds(List<AgentStream> events) {
        List<AgentStream> permissionEvents = events.stream()
                .filter(event -> event.status() == AgentStream.Status.PERMISSION_REQUESTED)
                .toList();
        if (permissionEvents.isEmpty()) {
            return List.of();
        }
        try {
            JsonNode array = new ObjectMapper()
                    .readTree(permissionEvents.get(permissionEvents.size() - 1).content());
            List<String> ids = new ArrayList<>();
            array.forEach(node -> ids.add(node.path("toolCallId").asText()));
            return ids;
        } catch (Exception e) {
            throw new IllegalStateException("权限请求解析失败", e);
        }
    }

    private static List<AgentStream> answer(AgentSession session, String toolCallId, String toolName, String arguments) {
        AgentInterruptHandle handle = new AgentInterruptHandle(
                RUN_ID, toolCallId, toolName, arguments, null,
                AgentInterruptHandle.Decision.APPROVED, null);
        return session.handleToolApproval(new AgentInterruptHandle[]{handle})
                .collectList().block(Duration.ofSeconds(15));
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

    /**
     * 第一次调用返回同一条 assistant 消息里的两个 shell 工具调用，之后返回普通文本。
     */
    static class TwoShellCallsMockChatModel implements ChatModel {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public ChatResponse call(Prompt prompt) {
            int call = calls.incrementAndGet();
            AssistantMessage message;
            if (call == 1) {
                message = AssistantMessage.builder()
                        .content("")
                        .toolCalls(List.of(
                                new AssistantMessage.ToolCall("call_shell_1", "function", "shell",
                                        "{\"command\":\"cat /etc/hosts\"}"),
                                new AssistantMessage.ToolCall("call_shell_2", "function", "shell",
                                        "{\"command\":\"head -n 1 /etc/hostname\"}")))
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
