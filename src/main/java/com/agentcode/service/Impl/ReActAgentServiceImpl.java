package com.agentcode.service.Impl;

import com.agentcode.agent.AgentLoop;
import com.agentcode.agent.AgentResult;
import com.agentcode.context.AgentContext;
import com.agentcode.service.ReactAgentService;
import com.agentcode.store.InMemoryAgentContextStore;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ReActAgentServiceImpl implements ReactAgentService {

    private final AgentLoop agentLoop;
    private final InMemoryAgentContextStore agentContextStore;

    public AgentResult run(String goal, String runId) {
        AgentContext agentContext;
        if (agentContextStore.find(runId).isEmpty()) {
            agentContext = AgentContext.builder()
                    .runId(runId)
                    .goal(goal)
                    .build();
        }else {
            agentContext = agentContextStore.find(runId).get();
        }
        return agentLoop.run(agentContext);
    }

    //TODO 获取历史消息

}
