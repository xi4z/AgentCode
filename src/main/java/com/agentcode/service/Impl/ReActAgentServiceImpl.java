package com.agentcode.service.Impl;

import com.agentcode.agent.AgentInterruptHandle;
import com.agentcode.agent.AgentSession;
import com.agentcode.agent.AgentStream;
import com.agentcode.context.AgentContext;
import com.agentcode.exception.*;
import com.agentcode.service.ReactAgentService;
import com.agentcode.session.AgentSessionRegistry;
import com.agentcode.store.InMemoryAgentContextStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ReActAgentServiceImpl implements ReactAgentService {

    private final InMemoryAgentContextStore agentContextStore;
    private final AgentSessionRegistry sessionRegistry;


    @Override
    public Flux<AgentStream> startNewSession(String goal, String workspace) {
        AgentContext context = AgentContext.builder()
                .runId(UUID.randomUUID().toString())
                .workspace(workspace)
                .goal(goal).build();
        agentContextStore.save(context.getRunId(), context);
        return run(goal, context.getRunId());
    }

    @Override
    public Flux<AgentStream> run(String goal, String runId) {
        AgentContext agentContext = getAgentContext(runId);
        AgentSession session = sessionRegistry.getOrCreate(runId, agentContext);
        return session.run(goal);
    }

    @Override
    public void stop(String runId) {
        // 先检查能否拿到上下文且不抛出错误
        getAgentContext(runId);
        getAgentSession(runId).stop();

    }

    @Override
    public Flux<AgentStream> handleInterrupt(AgentInterruptHandle handle) {
        return getAgentSession(handle.getRunId())
                .handleAgentInterrupt(new AgentInterruptHandle[]{handle});
    }

    @Override
    public void interrupt(String runId, String guidanceMessage) {
        // 先确定这个 runId 是否存在
        AgentContext agentContext = getAgentContext(runId);
        getAgentSession(runId).interrupt(guidanceMessage);
    }

    private AgentContext getAgentContext(String runId) {
        if (agentContextStore.find(runId).isEmpty()) {
            throw new AgentContextNotFoundException(runId);
        }
        return agentContextStore.find(runId).get();
    }

    private AgentSession getAgentSession(String runId) {
        return sessionRegistry.get(runId);
    }
}
