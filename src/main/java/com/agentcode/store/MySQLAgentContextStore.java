package com.agentcode.store;

import com.agentcode.agent.AgentContext;
import com.agentcode.entity.Context;
import com.agentcode.mapper.ContextMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 基于 MyBatis Plus 的 MySQL 上下文存储实现。
 */
@Component
@RequiredArgsConstructor
public class MySQLAgentContextStore implements AgentContextStore {

    private final ContextMapper contextMapper;

    @Override
    public void save(String runId, AgentContext context) {
        Context entity = toEntity(runId, context);
        if (contextMapper.selectById(runId) == null) {
            contextMapper.insert(entity);
        } else {
            contextMapper.updateById(entity);
        }
    }

    @Override
    public Optional<AgentContext> find(String runId) {
        Context entity = contextMapper.selectById(runId);
        if (entity == null) {
            return Optional.empty();
        }
        return Optional.of(toAgentContext(entity));
    }

    @Override
    public void remove(String runId) {
        contextMapper.deleteById(runId);
    }

    private Context toEntity(String runId, AgentContext context) {
        Context entity = new Context();
        entity.setRunId(runId);
        entity.setGoal(context.getGoal());
        entity.setWorkspace(context.getWorkspace());
        entity.setSessionNote(context.getSessionNotes() == null ? null : context.getSessionNotes().toString());
        return entity;
    }

    private AgentContext toAgentContext(Context entity) {
        AgentContext context = new AgentContext(
                entity.getRunId(),
                entity.getGoal(),
                entity.getWorkspace(),
                null // 全局上下文文件路径由 AgentCodeProperties 在装配阶段注入
        );
        if (entity.getSessionNote() != null) {
            context.setSessionNotes(new StringBuilder(entity.getSessionNote()));
        }
        return context;
    }
}
