package com.agentcode.service.Impl;

import com.agentcode.agent.AgentLoop;
import com.agentcode.agent.AgentStream;
import com.agentcode.context.AgentContext;
import com.agentcode.service.ReactAgentService;
import com.agentcode.store.InMemoryAgentContextStore;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

@Service
@RequiredArgsConstructor
public class ReActAgentServiceImpl implements ReactAgentService {

    private final AgentLoop agentLoop;
    private final InMemoryAgentContextStore agentContextStore;

    public Flux<AgentStream> run(String goal, String runId) {
        AgentContext agentContext;
        if (agentContextStore.find(runId).isEmpty()) {
            agentContext = AgentContext.builder()
                    .runId(runId)
                    .goal(goal)
                    .build();
        } else {
            agentContext = agentContextStore.find(runId).get();
        }

        if (agentContext.getWorkspace() == null || agentContext.getWorkspace().isBlank()) {
            agentContext.setWorkspace(System.getProperty("user.dir"));
        }

        try {
            return agentLoop.run(agentContext);
        } catch (GraphRunnerException e) {
            return Flux.error(e);
        }
    }

    @Override
    public void stop(String runId) {

    }
}
