package com.agentcode.agent.hooks;

import com.agentcode.agent.AgentTrace;
import com.agentcode.agent.context.AgentContext;
import com.agentcode.session.SessionEnum;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.hook.ModelHook;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 每次模型调用的计时与计数。
 *
 * <p>账记在 {@code RunnableConfig.context()} 上（key 见 {@link SessionEnum}），跟着 run 走；
 * {@link AgentTrace} 只管按统一格式打日志。
 * token 用量不在这里：{@code _TOKEN_USAGE_} 被 GraphRunnerContext 截成私有字段、不进 state，
 * 用量由会话层从 {@code NodeOutput.tokenUsage()} 侧补记。
 */
public class ModelPerformanceHook extends ModelHook {

    /** config.metadata() 里 AgentContext 的键（SessionEnum.AGENT_CONTEXT），用于取 runId */
    private final String contextKey;

    public ModelPerformanceHook(String contextKey) {
        this.contextKey = contextKey;
    }

    @Override
    public String getName() {
        return "model_performance";
    }

    @Override
    public CompletableFuture<Map<String, Object>> beforeModel(OverAllState state, RunnableConfig config) {
        config.context().put(SessionEnum.SINGLE_TIME.getCode(), System.currentTimeMillis());
        return super.beforeModel(state, config);
    }

    @Override
    public CompletableFuture<Map<String, Object>> afterModel(OverAllState state, RunnableConfig config) {
        Object start = config.context().get(SessionEnum.SINGLE_TIME.getCode());
        if (!(start instanceof Long startTime)) {
            return super.afterModel(state, config);
        }

        long duration = System.currentTimeMillis() - startTime;
        long totalCalls = number(config, SessionEnum.TOTAL_COUNT) + 1;
        long totalTime = number(config, SessionEnum.TOTAL_DURATION) + duration;
        config.context().put(SessionEnum.TOTAL_COUNT.getCode(), totalCalls);
        config.context().put(SessionEnum.TOTAL_DURATION.getCode(), totalTime);

        AgentTrace.modelCall(runId(config), duration, totalCalls, totalTime);
        return super.afterModel(state, config);
    }

    /**
     * runId 优先取 metadata 里的 AgentContext：审批恢复时重建的 config 上没有 threadId，
     * 只认 threadId 的话恢复后的模型调用会打成 "RunId -"。
     */
    private String runId(RunnableConfig config) {
        return config.metadata(contextKey)
                .filter(AgentContext.class::isInstance)
                .map(agentContext -> ((AgentContext) agentContext).getRunId())
                .orElseGet(() -> config.threadId().orElse(null));
    }

    private long number(RunnableConfig config, SessionEnum key) {
        Object value = config.context().get(key.getCode());
        return value instanceof Number number ? number.longValue() : 0L;
    }
}
