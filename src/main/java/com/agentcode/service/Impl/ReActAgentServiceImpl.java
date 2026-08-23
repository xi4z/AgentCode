package com.agentcode.service.Impl;

import com.agentcode.dto.AgentInterruptHandle;
import com.agentcode.session.AgentSession;
import com.agentcode.dto.AgentStream;
import com.agentcode.context.AgentContext;
import com.agentcode.exception.*;
import com.agentcode.service.ReactAgentService;
import com.agentcode.store.InMemoryAgentContextStore;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
public class ReActAgentServiceImpl implements ReactAgentService {

    private final InMemoryAgentContextStore agentContextStore;
    private final ChatModel chatModel;
    private final MemorySaver memorySaver;
    private final ConcurrentHashMap<String, AgentSession> sessions = new  ConcurrentHashMap<>();


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
        AgentSession session;
        try {
            session = this.getAgentSession(runId);
        } catch (SessionNotFoundException e) {
            session = new AgentSession(agentContext, chatModel, memorySaver);
            sessions.put(runId, session);
        }

        // TODO 在限定时间内可清理超过半个小时不使用的session 或在到达额度时清理掉最长时间不使用的session
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
        AgentSession session = this.sessions.get(runId);
        if (session == null) {
            throw new SessionNotFoundException(runId);
        }
        return session;
    }
}
