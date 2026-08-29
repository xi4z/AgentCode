package com.agentcode.store;

import com.agentcode.context.AgentContext;

import java.util.Optional;

public interface AgentContextStore {
    public void save(String runId, AgentContext context);

    public Optional<AgentContext> find(String runId);

    public void remove(String runId);
}
