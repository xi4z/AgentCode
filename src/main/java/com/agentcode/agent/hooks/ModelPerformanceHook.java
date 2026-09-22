package com.agentcode.agent.hooks;

import com.agentcode.agent.AgentTrace;
import com.agentcode.session.SessionEnum;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.hook.ModelHook;
import lombok.NoArgsConstructor;

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
@NoArgsConstructor
public class ModelPerformanceHook extends ModelHook {

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

        AgentTrace.modelCall(config.threadId().orElse(null), duration, totalCalls, totalTime);
        return super.afterModel(state, config);
    }

    private long number(RunnableConfig config, SessionEnum key) {
        Object value = config.context().get(key.getCode());
        return value instanceof Number number ? number.longValue() : 0L;
    }
}
