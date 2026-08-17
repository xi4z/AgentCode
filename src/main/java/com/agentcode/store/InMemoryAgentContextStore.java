package com.agentcode.store;

import com.agentcode.context.AgentContext;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class InMemoryAgentContextStore {
    private final Map<String, AgentContext> store = new ConcurrentHashMap<>();

    public void save(String runId, AgentContext context) {
        store.put(runId, context);
    }

    public Optional<AgentContext> find(String runId) {
        return Optional.ofNullable(store.get(runId));
    }

    public void remove(String runId) {
        store.remove(runId);
    }

}
