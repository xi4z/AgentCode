package com.agentcode.session;

import com.agentcode.context.AgentContext;
import com.agentcode.dto.AgentStream;
import com.agentcode.factory.AgentHookBuilder;
import com.agentcode.factory.AgentSessionFactory;
import com.agentcode.factory.AgentToolBuilder;
import com.agentcode.factory.SessionBuildOptions;
import com.agentcode.properties.AgentCodeProperties;
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
 * 验证 ModelCallLimitHook：同一轮 Agent 执行中模型调用达到 runLimit 后应正常结束，不无限循环。
 */
class ModelCallLimitIntegrationTest {

    @Test
    void shouldStopAfterRunLimit() throws Exception {
        Path workspace = Files.createTempDirectory("agentcode-model-call-limit");
        try {
            AgentContext context = AgentContext.builder()
                    .runId("model-call-limit-test")
                    .workspace(workspace.toString())
                    .goal("反复调用 appendNote 直到停止")
                    .build();

            AtomicInteger modelCalls = new AtomicInteger();
            ChatModel mock = new ChatModel() {
                @Override
                public ChatResponse call(Prompt prompt) {
                    int call = modelCalls.incrementAndGet();
                    AssistantMessage message = AssistantMessage.builder()
                            .content("")
                            .toolCalls(List.of(new AssistantMessage.ToolCall(
                                    "call_append_" + call,
                                    "function",
                                    "appendNote",
                                    "{\"content\":\"x\"}"
                            )))
                            .build();
                    return new ChatResponse(List.of(new Generation(message)));
                }

                @Override
                public Flux<ChatResponse> stream(Prompt prompt) {
                    return Flux.just(call(prompt));
                }
            };

            AgentCodeProperties properties = propertiesWithMaxSteps(10);
            AgentSessionFactory factory = new AgentSessionFactory(
                    mock, new MemorySaver(), properties,
                    new AgentHookBuilder(mock, properties),
                    new AgentToolBuilder(mock));
            AgentSession session = factory.create(context, SessionBuildOptions.builder()
                    .approvalTools(List.of())
                    .build());
            List<AgentStream> events = session.run("反复调用 appendNote 直到停止")
                    .collectList()
                    .block(Duration.ofSeconds(20));

            assertThat(events).isNotNull();
            // runLimit 取自 agentcode.agent.max-steps，达到限制后不应继续无限调用
            assertThat(modelCalls.get()).isLessThanOrEqualTo(10);
        } finally {
            deleteRecursively(workspace);
        }
    }

    private static AgentCodeProperties propertiesWithMaxSteps(int maxSteps) {
        AgentCodeProperties properties = new AgentCodeProperties();
        properties.getAgent().setMaxSteps(maxSteps);
        return properties;
    }

    private void deleteRecursively(Path path) throws Exception {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (var stream = Files.walk(path)) {
            stream.sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                    // ignore
                }
            });
        }
    }
}
