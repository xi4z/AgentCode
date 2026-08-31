package com.agentcode.hooks;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.hook.AgentHook;
import com.alibaba.cloud.ai.graph.agent.hook.HookPosition;
import com.alibaba.cloud.ai.graph.agent.hook.HookPositions;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

/**
 * Lightweight observability hook for final checkpoint state after agent run.
 */
@Slf4j
@HookPositions({HookPosition.AFTER_AGENT})
public class CheckpointAgentMetricsHook extends AgentHook {
    private static final int MAX_STATE_VALUE_LENGTH = 900;

    @Override
    public String getName() {
        return "checkpoint_agent_metrics_hook";
    }

    @Override
    public java.util.concurrent.CompletableFuture<Map<String, Object>> afterAgent(OverAllState state, RunnableConfig config) {
        if (log.isDebugEnabled()) {
            log.debug("AUDIT_CHECKPOINT_STATE phase=AFTER_AGENT runId={} agent={} keys={} values={}",
                    config == null ? null : config.threadId().orElse(null),
                    getAgentName(),
                    state.data().keySet(),
                    compactState(state.data()));
        }
        log.info("AUDIT_AGENT_CHECKPOINT_SUMMARY runId={} iteration={} tokenUsage={} keys={}",
                config == null ? null : config.threadId().orElse(null),
                state.value("iterations").orElse(null),
                state.value("tokenUsage").orElse(null),
                state.data().keySet());
        return super.afterAgent(state, config);
    }

    private String compactState(Map<String, Object> data) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            sb.append(entry.getKey()).append('=');
            Object value = entry.getValue();
            String text;
            if (value instanceof java.util.Collection<?> collection) {
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
}