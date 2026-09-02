package com.agentcode.service.Impl;

import com.agentcode.agent.AgentContext;
import com.agentcode.dto.AgentInterruptHandle;
import com.agentcode.entity.Context;
import com.agentcode.mapper.ContextMapper;
import com.agentcode.properties.AgentCodeProperties;
import com.agentcode.vo.AgentStream;
import com.agentcode.exception.AgentContextNotFoundException;
import com.agentcode.factory.AgentSessionFactory;
import com.agentcode.registry.AgentSessionRegistry;
import com.agentcode.service.ReactAgentService;
import com.agentcode.agent.AgentSession;
import com.agentcode.store.AgentContextStore;
import com.agentcode.vo.ContextVo;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ReActAgentServiceImpl implements ReactAgentService {

    private final AgentContextStore agentContextStore;
    private final AgentSessionFactory agentSessionFactory;
    private final AgentSessionRegistry agentSessionRegistry;
    private final AgentCodeProperties aProperties;
    private final ContextMapper contextMapper;

    @Override
    public Flux<AgentStream> startNewSession(String goal, String workspace) {
        String runId = createSession(goal, workspace);
        return run(goal, runId);
    }

    @Override
    public String createSession(String goal, String workspace) {
        Context currContext = new Context();
        currContext.setRunId(UUID.randomUUID().toString());
        currContext.setGoal(goal);
        currContext.setWorkspace(workspace);
        // 只保留一次持久化路径：store.save 内部已是单条 INSERT ... ON DUPLICATE KEY UPDATE 原子 upsert，
        // 不再先 contextMapper.insert 再 store.save（重复往返且可能重复插入）
        AgentContext context = new AgentContext(
            currContext
        );
        agentContextStore.save(context.getRunId(), context);
        return context.getRunId();
    }

    @Override
    public boolean sessionExists(String runId) {
        return runId != null && agentContextStore.find(runId).isPresent();
    }

    @Override
    public Flux<AgentStream> run(String goal, String runId) {
        // 先查内存中已有会话：命中直接复用，不重载持久化 context（保留会话最新状态）
        AgentSession existing = agentSessionRegistry.getOrNull(runId);
        if (existing != null) {
            return existing.run(goal);
        }
        // 未命中才加载持久化 context 并创建：
        // 创建（重型工厂装配 ReactAgent）在 Registry.getOrCreate 内已移到锁外执行，
        // 并发竞争失败者丢弃自建实例、使用胜者写入的会话
        AgentContext agentContext = getAgentContext(runId);
        // 空闲会话的回收与审批超时放弃由 AgentSessionMaintenance 定时处理
        AgentSession session = agentSessionRegistry.getOrCreate(runId,
                () -> agentSessionFactory.create(agentContext));
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
        getAgentContext(runId);
        agentSessionRegistry.get(runId).interrupt(guidanceMessage);
    }

    public List<ContextVo> getRunIds(){
        QueryWrapper<Context> queryWrapper = new QueryWrapper<>();
        queryWrapper.orderByDesc("updated_at");
        List<Context> contexts = contextMapper.selectList(queryWrapper);
        List<ContextVo> vos = new ArrayList<>();
        for (Context context : contexts) {
            vos.add(context.toVo());
        }
        return vos;

    }




    private AgentContext getAgentContext(String runId) {
        return agentContextStore.find(runId)
                .orElseThrow(() -> new AgentContextNotFoundException(runId));
    }
}
