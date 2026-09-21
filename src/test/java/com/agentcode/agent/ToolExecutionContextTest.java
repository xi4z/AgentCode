package com.agentcode.agent;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.ModelHook;
import com.alibaba.cloud.ai.graph.agent.hook.shelltool.ShellToolAgentHook;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallResponse;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolInterceptor;
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
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ToolCallRequest.getExecutionContext() 里到底有什么：一个 RunnableConfig + 一个 OverAllState。
 * 用探针拦截器把两者的实际内容打出来，顺便断言 threadId 就是本次 run 的 threadId。
 */
class ToolExecutionContextTest {

    @Test
    void shouldExposeConfigAndStateToToolInterceptor() throws Exception {
        Path workspace = Files.createTempDirectory("tool-execution-context-test");

        try {
            ProbeToolInterceptor probe = new ProbeToolInterceptor();
            StateProbeModelHook stateProbe = new StateProbeModelHook();
            MemorySaver saver = new MemorySaver();
            ShellTool2 shellTool2 = ShellTool2.builder(workspace.toString()).build();

            ReactAgent agent = ReactAgent.builder()
                    .name("execution_context_agent")
                    .model(new OneShellCallThenTextModel())
                    .saver(saver)
                    .tools(List.of(GrepSearchTool.builder(workspace.toString()).build()))
                    .hooks(List.of(ShellToolAgentHook.builder()
                                    .shellTool2(shellTool2)
                                    .shellToolName("shell")
                                    .build(),
                            stateProbe))
                    .interceptors(List.of(probe))
                    .build();

            RunnableConfig config = RunnableConfig.builder()
                    .threadId("execution-context-run")
                    .build();
            config.context().put("__PROBE_CONTEXT_KEY__", "sentinel");

            agent.stream("跑一条命令", config).blockLast(Duration.ofSeconds(20));

            System.out.println("=== executionContext ===");
            System.out.println("threadId     = " + probe.threadId);
            System.out.println("checkpointId = " + probe.checkpointId);
            System.out.println("state keys   = " + probe.stateKeys);
            System.out.println("config.context keys = " + probe.configContextKeys);
            System.out.println("config.metadata keys = " + probe.configMetadataKeys);
            System.out.println("afterModel state keys = " + stateProbe.afterModelStateKeys);
            System.out.println("afterModel config.context keys = " + stateProbe.afterModelContextKeys);
            System.out.println("checkpoint state keys = "
                    + new TreeSet<>(saver.get(config).orElseThrow().getState().keySet()));

            assertThat(probe.threadId).isEqualTo("execution-context-run");
            assertThat(probe.stateKeys).contains("messages");
            assertThat(probe.configContextKeys).contains("__PROBE_CONTEXT_KEY__");
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

    /** 探针：记录 executionContext 暴露出来的东西，然后原样放行 */
    static class ProbeToolInterceptor extends ToolInterceptor {

        volatile String threadId;
        volatile String checkpointId;
        volatile Set<String> stateKeys = Set.of();
        volatile Set<String> configContextKeys = Set.of();
        volatile Set<String> configMetadataKeys = Set.of();

        @Override
        public String getName() {
            return "probe_tool_interceptor";
        }

        @Override
        public ToolCallResponse interceptToolCall(ToolCallRequest request, ToolCallHandler handler) {
            request.getExecutionContext().ifPresent(ctx -> {
                threadId = ctx.threadId().orElse(null);
                checkpointId = ctx.checkpointId().orElse(null);
                OverAllState state = ctx.state();
                stateKeys = new TreeSet<>(state.data().keySet());
                configContextKeys = new TreeSet<>(ctx.config().context().keySet());
                configMetadataKeys = new TreeSet<>(ctx.config().metadata().orElse(java.util.Map.of()).keySet());
            });
            return handler.call(request);
        }
    }

    /** 探针：看 ModelHook.afterModel 拿到的 state / config.context 里有什么 */
    static class StateProbeModelHook extends ModelHook {

        volatile Set<String> afterModelStateKeys = Set.of();
        volatile Set<String> afterModelContextKeys = Set.of();

        @Override
        public String getName() {
            return "state_probe_model_hook";
        }

        @Override
        public CompletableFuture<Map<String, Object>> afterModel(OverAllState state, RunnableConfig config) {
            afterModelStateKeys = new TreeSet<>(state.data().keySet());
            afterModelContextKeys = new TreeSet<>(config.context().keySet());
            return super.afterModel(state, config);
        }
    }

    /** 第一次返回一个 shell 工具调用，之后返回普通文本 */
    static class OneShellCallThenTextModel implements ChatModel {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public ChatResponse call(Prompt prompt) {
            int call = calls.incrementAndGet();
            AssistantMessage message = call == 1
                    ? AssistantMessage.builder()
                            .content("")
                            .toolCalls(List.of(new AssistantMessage.ToolCall(
                                    "call_1", "function", "shell", "{\"command\":\"echo probe\"}")))
                            .build()
                    : new AssistantMessage("done " + call);
            return new ChatResponse(List.of(new Generation(message)));
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.just(call(prompt));
        }
    }
}
