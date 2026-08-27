package com.agentcode.registry;

import com.agentcode.exception.SessionNotFoundException;
import com.agentcode.session.AgentSession;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * AgentSession 注册表：按 runId 管理内存中的活跃会话。
 */
@Component
public class AgentSessionRegistry {

    private static final class SessionEntry {
        final AgentSession session;
        volatile long lastAccessAt;

        SessionEntry(AgentSession session) {
            this.session = session;
            this.lastAccessAt = System.currentTimeMillis();
        }
    }

    private final ConcurrentHashMap<String, SessionEntry> sessions = new ConcurrentHashMap<>();

    /**
     * 获取或创建会话，保证同一 runId 并发下只会创建一个 AgentSession。
     */
    public AgentSession getOrCreate(String runId, Supplier<AgentSession> supplier) {
        SessionEntry entry = sessions.computeIfAbsent(runId, id -> new SessionEntry(supplier.get()));
        entry.lastAccessAt = System.currentTimeMillis();
        return entry.session;
    }

    public AgentSession get(String runId) {
        SessionEntry entry = sessions.get(runId);
        if (entry == null) {
            throw new SessionNotFoundException(runId);
        }
        entry.lastAccessAt = System.currentTimeMillis();
        return entry.session;
    }

    public void remove(String runId) {
        sessions.remove(runId);
    }

    public int size() {
        return sessions.size();
    }

    /**
     * 清理超过 maxIdle 且当前处于 FREE 状态的会话。
     */
    public void evictIdle(Duration maxIdle) {
        long threshold = System.currentTimeMillis() - maxIdle.toMillis();
        sessions.entrySet().removeIf(entry -> {
            SessionEntry value = entry.getValue();
            return value.lastAccessAt < threshold
                    && value.session.getStatus() == AgentSession.Status.FREE;
        });
    }

    /**
     * 返回当前注册表的只读快照，便于运维/测试观察。
     */
    public Map<String, AgentSession> snapshot() {
        Map<String, AgentSession> snapshot = new ConcurrentHashMap<>();
        sessions.forEach((runId, entry) -> snapshot.put(runId, entry.session));
        return Map.copyOf(snapshot);
    }
}
