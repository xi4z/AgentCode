package com.agentcode.store;

import com.agentcode.agent.AgentContext;

import java.util.Optional;

public class MySQLAgentContextStore implements AgentContextStore {
    @Override
    public void save(String runId, AgentContext context) {

    }

    @Override
    public Optional<AgentContext> find(String runId) {
        return Optional.empty();
    }

    @Override
    public void remove(String runId) {

    }
}
