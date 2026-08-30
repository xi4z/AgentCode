package com.agentcode.hooks;

import com.agentcode.agent.AgentContext;
import com.agentcode.common.SessionConfigKeys;
import com.agentcode.store.AgentContextStore;
import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.hook.AgentHook;
import com.alibaba.cloud.ai.graph.agent.hook.Hook;
import com.alibaba.cloud.ai.graph.agent.hook.HookPosition;
import com.alibaba.cloud.ai.graph.agent.hook.HookPositions;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;


@HookPositions({HookPosition.AFTER_AGENT})
public class UpdateSessionNotesHook extends AgentHook {

    private final AgentContextStore agentContextStore;

    public UpdateSessionNotesHook(AgentContextStore agentContextStore) {
        this.agentContextStore = agentContextStore;
    }

    @Override
    public String getName() {
        return "update_session_notes_hook";
    }

    @Override
    public CompletableFuture<Map<String, Object>> afterAgent(OverAllState state, RunnableConfig config) {
        // 在完成后应更新 AgentContext 上下文, 如果在没有更新之前因非预期因素停止时, 进行处由 Saver 进行保存
        Optional<String> runId = config.threadId();
        if (runId.isEmpty()) {
            return super.afterAgent(state, config);
        }
        Object obj = config.context().get(SessionConfigKeys.AGENT_CONTEXT);
        if (!(obj instanceof AgentContext context)) {
            return super.afterAgent(state, config);

        }
        agentContextStore.save(runId.get(), context);
        return super.afterAgent(state, config);
    }
}
