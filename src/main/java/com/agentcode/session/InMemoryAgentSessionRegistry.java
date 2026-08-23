package com.agentcode.session;

import com.agentcode.agent.AgentSession;
import com.agentcode.context.AgentContext;
import com.agentcode.exception.SessionNotFoundException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于 ConcurrentHashMap 的 AgentSession 注册表实现。
 *
 * - 每个 runId 对应一个独立 AgentSession
 * - 空闲超过 IDLE_TIMEOUT 且状态为 FREE 的会话会被自动清理
 * - RUNNING / INTERRUPTED 会话不会被清理
 */
@Component
public class InMemoryAgentSessionRegistry implements AgentSessionRegistry {

    private static final Duration IDLE_TIMEOUT = Duration.ofMinutes(30);

    private final Map<String, SessionEntry> sessions = new ConcurrentHashMap<>();
    private final AgentSessionFactory agentSessionFactory;

    public InMemoryAgentSessionRegistry(AgentSessionFactory agentSessionFactory) {
        this.agentSessionFactory = agentSessionFactory;
    }

    /**
     * 获取或创建会话。
     * computeIfAbsent 保证并发下同一个 runId 只会创建一个 AgentSession。
     */
    @Override
    public AgentSession getOrCreate(String runId, AgentContext context) {
        SessionEntry entry = sessions.computeIfAbsent(runId, key ->
                new SessionEntry(agentSessionFactory.create(context))
        );
        entry.touch();
        return entry.session();
    }

    @Override
    public AgentSession get(String runId) {
        SessionEntry entry = sessions.get(runId);
        if (entry == null) {
            throw new SessionNotFoundException(runId);
        }
        entry.touch();
        return entry.session();
    }

    @Override
    public void remove(String runId) {
        sessions.remove(runId);
    }

    @Override
    public boolean contains(String runId) {
        return sessions.containsKey(runId);
    }

    /**
     * 定时清理空闲会话，避免内存中 AgentSession 无限堆积。
     * 只清理“空闲超过 IDLE_TIMEOUT 且状态为 FREE”的会话；
     * RUNNING / INTERRUPTED 会话不会被清理，避免误杀正在执行或等待审批的任务。
     */
    @Override
    @Scheduled(fixedDelay = 60_000)
    public void cleanup() {
        sessions.entrySet().removeIf(entry -> {
            AgentSession session = entry.getValue().session();
            boolean idle = entry.getValue().isIdle(IDLE_TIMEOUT);
            return idle && session.getStatus() == AgentSession.Status.FREE;
        });
    }

    @Override
    public int size() {
        return sessions.size();
    }

    private static class SessionEntry {

        private final AgentSession session;
        private volatile Instant lastAccessTime;

        private SessionEntry(AgentSession session) {
            this.session = session;
            this.lastAccessTime = Instant.now();
        }

        private AgentSession session() {
            return session;
        }

        private void touch() {
            this.lastAccessTime = Instant.now();
        }

        private boolean isIdle(Duration timeout) {
            return Duration.between(lastAccessTime, Instant.now()).compareTo(timeout) > 0;
        }
    }
}
