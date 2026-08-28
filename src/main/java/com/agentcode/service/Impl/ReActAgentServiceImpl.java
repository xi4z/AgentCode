package com.agentcode.service.Impl;

import com.agentcode.context.AgentContext;
import com.agentcode.dto.AgentInterruptHandle;
import com.agentcode.dto.AgentStream;
import com.agentcode.exception.AgentContextNotFoundException;
import com.agentcode.factory.AgentSessionFactory;
import com.agentcode.registry.AgentSessionRegistry;
import com.agentcode.service.ReactAgentService;
import com.agentcode.session.AgentSession;
import com.agentcode.store.InMemoryAgentContextStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ReActAgentServiceImpl implements ReactAgentService {

    private final InMemoryAgentContextStore agentContextStore;
    private final AgentSessionFactory agentSessionFactory;
    private final AgentSessionRegistry agentSessionRegistry;

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
        AgentSession session = agentSessionRegistry.getOrCreate(runId,
                () -> agentSessionFactory.create(agentContext));
        // TODO 在限定时间内可清理超过半个小时不使用的session 或在到达额度时清理掉最长时间不使用的session
        return session.run(goal);
    }

    @Override
    public void stop(String runId) {
        // 先检查能否拿到上下文且不抛出错误
        getAgentContext(runId);
        agentSessionRegistry.get(runId).stop();
    }

    @Override
    public Flux<AgentStream> handleInterrupt(String runId, AgentInterruptHandle... handles) {
        AgentInterruptHandle[] submitted = handles == null ? new AgentInterruptHandle[0] : handles;
        for (AgentInterruptHandle handle : submitted) {
            if (handle == null) {
                throw new IllegalArgumentException("审批决定不能为空");
            }
            if (runId != null && handle.getRunId() != null && !runId.equals(handle.getRunId())) {
                throw new IllegalArgumentException("一次审批提交必须属于同一会话: " + runId + " != " + handle.getRunId());
            }
        }
        return agentSessionRegistry.get(runId).handleAgentInterrupt(submitted);
    }

    @Override
    public void interrupt(String runId, String guidanceMessage) {
        // 先确定这个 runId 是否存在
        AgentContext agentContext = getAgentContext(runId);
        agentSessionRegistry.get(runId).interrupt(guidanceMessage);
    }

    private AgentContext getAgentContext(String runId) {
        if (agentContextStore.find(runId).isEmpty()) {
            throw new AgentContextNotFoundException(runId);
        }
        return agentContextStore.find(runId).get();
    }
}
