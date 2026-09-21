package com.agentcode.agent;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.ModelHook;
import com.alibaba.cloud.ai.graph.agent.hook.shelltool.ShellToolAgentHook;
import com.alibaba.cloud.ai.graph.agent.tools.GrepSearchTool;
import com.alibaba.cloud.ai.graph.agent.tools.ShellTool2;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ModelHook 的触发时机：按"模型调用次数"触发，不按工具调用次数。
 *
 * 一次模型调用里带 2 个 shell 工具调用时，beforeModel / afterModel 各只触发一次；
 * 工具执行完回到模型节点后再各触发一次。所以总计 2 次模型调用 = 2 次 beforeModel + 2 次 afterModel。
 */
class ModelHookInvocationTest {

    @Test
    void shouldFireOncePerModelCallNotPerToolCall() throws Exception {
        Path workspace = Files.createTempDirectory("model-hook-invocation-test");

        try {
            CountingModelHook hook = new CountingModelHook();
            ShellTool2 shellTool2 = ShellTool2.builder(workspace.toString()).build();

            ReactAgent agent = ReactAgent.builder()
                    .name("hook_count_agent")
                    .model(new TwoShellCallsThenTextModel())
                    .saver(new MemorySaver())
                    .tools(List.of(GrepSearchTool.builder(workspace.toString()).build()))
                    .hooks(List.of(
                            ShellToolAgentHook.builder()
                                    .shellTool2(shellTool2)
                                    .shellToolName("shell")
                                    .build(),
                            hook
                    ))
                    .build();

            agent.stream("跑两个命令", RunnableConfig.builder()
                            .threadId("model-hook-invocation-run")
                            .build())
                    .blockLast(Duration.ofSeconds(20));

            // 两次 LLM 往返：第一次带 2 个工具调用，第二次收尾
            assertThat(hook.beforeModelCalls.get()).isEqualTo(2);
            assertThat(hook.afterModelCalls.get()).isEqualTo(2);
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

    /** 只记次数，行为与默认实现一致（返回空增量） */
    static class CountingModelHook extends ModelHook {
        private final AtomicInteger beforeModelCalls = new AtomicInteger();
        private final AtomicInteger afterModelCalls = new AtomicInteger();

        @Override
        public String getName() {
            return "counting_model_hook";
        }

        @Override
        public CompletableFuture<Map<String, Object>> beforeModel(OverAllState state, RunnableConfig config) {
            beforeModelCalls.incrementAndGet();
            return super.beforeModel(state, config);
        }

        @Override
        public CompletableFuture<Map<String, Object>> afterModel(OverAllState state, RunnableConfig config) {
            afterModelCalls.incrementAndGet();
            return super.afterModel(state, config);
        }
    }

    /** 第一次调用返回同一条消息里的两个 shell 工具调用，之后返回普通文本 */
    static class TwoShellCallsThenTextModel implements ChatModel {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public ChatResponse call(Prompt prompt) {
            int call = calls.incrementAndGet();
            AssistantMessage message;
            if (call == 1) {
                message = AssistantMessage.builder()
                        .content("")
                        .toolCalls(List.of(
                                new AssistantMessage.ToolCall("call_1", "function", "shell",
                                        "{\"command\":\"echo one\"}"),
                                new AssistantMessage.ToolCall("call_2", "function", "shell",
                                        "{\"command\":\"echo two\"}")))
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
