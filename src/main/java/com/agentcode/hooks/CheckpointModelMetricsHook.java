package com.agentcode.hooks;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.hook.ModelHook;
import com.alibaba.cloud.ai.graph.agent.hook.HookPosition;
import com.alibaba.cloud.ai.graph.agent.hook.HookPositions;
import lombok.extern.slf4j.Slf4j;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Lightweight observability hook for model/checkpoint state.
 *
 * It only logs metrics from the graph state / checkpoint state. It does not change execution.
 */
@Slf4j
@HookPositions({HookPosition.BEFORE_MODEL, HookPosition.AFTER_MODEL})
public class CheckpointModelMetricsHook extends ModelHook {
    private static final int MAX_STATE_VALUE_LENGTH = 900;
    private final AtomicInteger modelCalls = new AtomicInteger();

    @Override
    public String getName() {
        return "checkpoint_model_metrics_hook";
    }

    @Override
    public java.util.concurrent.CompletableFuture<Map<String, Object>> beforeModel(OverAllState state, RunnableConfig config) {
        logState("BEFORE_MODEL", state, config);
        return super.beforeModel(state, config);
    }

    @Override
    public java.util.concurrent.CompletableFuture<Map<String, Object>> afterModel(OverAllState state, RunnableConfig config) {
        int calls = modelCalls.incrementAndGet();
        logState("AFTER_MODEL", state, config);
        log.info("AUDIT_MODEL_CALL_COMPLETED runId={} callNo={} iteration={} tokenUsage={} keys={}",
                threadId(config), calls, state.value("iterations").orElse(null),
                state.value("tokenUsage").orElse(null), state.data().keySet());
        return super.afterModel(state, config);
    }

    private void logState(String phase, OverAllState state, RunnableConfig config) {
        if (!log.isDebugEnabled()) {
            return;
        }
        Map<String, Object> data = state.data();
        log.debug("AUDIT_CHECKPOINT_STATE phase={} runId={} agent={} keys={} values={}",
                phase,
                threadId(config),
                getAgentName(),
                data.keySet(),
                compactState(data));
    }

    private String compactState(Map<String, Object> data) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            sb.append(entry.getKey()).append('=');
            Object value = entry.getValue();
            String text;
            if (value instanceof Collection<?> collection) {
                text = "size=" + collection.size();
            } else if (value instanceof Map<?, ?> map) {
                text = "mapKeys=" + map.keySet();
            } else {
                text = String.valueOf(value);
            }
            if (text.length() > MAX_STATE_VALUE_LENGTH) {
                text = text.substring(0, MAX_STATE_VALUE_LENGTH) + "...truncated";
            }
            sb.append(text).append("; ");
        }
        return sb.toString();
    }

    private String threadId(RunnableConfig config) {
        return config == null ? null : config.threadId().orElse(null);
    }
}