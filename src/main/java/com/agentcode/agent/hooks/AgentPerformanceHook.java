package com.agentcode.agent.hooks;

import com.agentcode.agent.AgentTrace;
import com.agentcode.agent.context.AgentContext;
import com.agentcode.session.SessionEnum;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.hook.AgentHook;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 一轮会话（一次 run / 一个回合）的计时与汇总。
 *
 * <p>账全部走 {@code config.context()}（key 见 {@link SessionEnum}）：调用次数、累计耗时、累计用量；
 * run 的开工时刻走 {@link AgentContext#getAgentStartMs()} —— 审批恢复会重建一张全新的 context 表，
 * 旧表里的开工时刻带不过去，wallClock 会假报 0。同一轮内 context 条目在节点之间一路可见
 * （实测 beforeAgent 清零的累计量 afterModel / afterAgent 读得到）。
 *
 * <p>注意 afterAgent 在"审批中断"时不会执行 —— 图在 HITL 处停下、没走到 end 节点，
 * 所以停在中断点上那一轮的收口由 AgentSession 补 {@code Run I}。
 */
public class AgentPerformanceHook extends AgentHook {

    /** config.metadata() 里 AgentContext 的键（SessionEnum.AGENT_CONTEXT），用于取 runId */
    private final String contextKey;

    public AgentPerformanceHook(String contextKey) {
        this.contextKey = contextKey;
    }

    @Override
    public String getName() {
        return "agent_performance";
    }

    @Override
    public CompletableFuture<Map<String, Object>> beforeAgent(OverAllState state, RunnableConfig config) {
        config.context().put(SessionEnum.TOTAL_COUNT.getCode(), 0L);
        config.context().put(SessionEnum.TOTAL_DURATION.getCode(), 0L);
        config.context().put(SessionEnum.TOTAL_USAGE.getCode(), 0L);
        AgentTrace.runEvent(runId(config), 'B', 0L, 0L, 0L);
        return super.beforeAgent(state, config);
    }

    @Override
    public CompletableFuture<Map<String, Object>> afterAgent(OverAllState state, RunnableConfig config) {
        AgentContext agentContext = config.metadata(contextKey)
                .filter(AgentContext.class::isInstance)
                .map(AgentContext.class::cast)
                .orElse(null);
        long start = agentContext == null ? 0L : agentContext.getAgentStartMs();
        AgentTrace.runEvent(runId(config), 'E',
                number(config, SessionEnum.TOTAL_COUNT),
                start == 0L ? 0L : System.currentTimeMillis() - start,
                number(config, SessionEnum.TOTAL_USAGE));
        return super.afterAgent(state, config);
    }

    /** runId 优先取 metadata 里的 AgentContext，取不到再退回 framework 的 threadId（factory 里两者同值） */
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
