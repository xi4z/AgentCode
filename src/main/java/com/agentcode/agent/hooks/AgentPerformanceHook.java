package com.agentcode.agent.hooks;

import com.agentcode.agent.AgentTrace;
import com.agentcode.session.SessionEnum;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.hook.AgentHook;
import lombok.NoArgsConstructor;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 一轮会话（一次 run / 一个回合）的计时与汇总。
 *
 * <p>账全部走 {@code config.context()}（key 见 {@link SessionEnum}）：开工时刻、调用次数、
 * 累计耗时、累计用量。框架在节点之间会派生新的 RunnableConfig 并拷贝 context 条目，
 * 但同一轮内这些条目一路可见（实测 beforeAgent 写的开工时刻 afterAgent 读得到）。
 *
 * <p>注意 afterAgent 在"审批中断"时不会执行 —— 图在 HITL 处停下、没走到 end 节点，
 * 所以中断挂起那一轮没有 SessionEnd。
 */
@NoArgsConstructor
public class AgentPerformanceHook extends AgentHook {

    @Override
    public String getName() {
        return "agent_performance";
    }

    @Override
    public CompletableFuture<Map<String, Object>> beforeAgent(OverAllState state, RunnableConfig config) {
        config.context().put(SessionEnum.TOTAL_COUNT.getCode(), 0L);
        config.context().put(SessionEnum.TOTAL_DURATION.getCode(), 0L);
        config.context().put(SessionEnum.TOTAL_USAGE.getCode(), 0L);
        config.context().put(SessionEnum.AGENT_START.getCode(), System.currentTimeMillis());
        AgentTrace.sessionStart(config.threadId().orElse(null));
        return super.beforeAgent(state, config);
    }

    @Override
    public CompletableFuture<Map<String, Object>> afterAgent(OverAllState state, RunnableConfig config) {
        long start = number(config, SessionEnum.AGENT_START);
        AgentTrace.sessionEnd(config.threadId().orElse(null),
                number(config, SessionEnum.TOTAL_COUNT),
                number(config, SessionEnum.TOTAL_DURATION),
                start == 0 ? 0L : System.currentTimeMillis() - start,
                number(config, SessionEnum.TOTAL_USAGE));
        return super.afterAgent(state, config);
    }

    private long number(RunnableConfig config, SessionEnum key) {
        Object value = config.context().get(key.getCode());
        return value instanceof Number number ? number.longValue() : 0L;
    }
}
