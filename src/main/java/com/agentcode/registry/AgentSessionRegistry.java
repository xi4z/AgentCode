package com.agentcode.registry;

import com.agentcode.exception.SessionNotFoundException;
import com.agentcode.agent.AgentSession;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
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
     *
     * <p>重型创建（supplier 可能装配整个 ReactAgent）在 ConcurrentHashMap 桶锁外执行，
     * 再以 putIfAbsent 提交：竞争失败者丢弃自建实例、使用胜者，
     * 避免 computeIfAbsent 在同桶锁内执行耗时创建而阻塞同桶其他 key。
     */
    public AgentSession getOrCreate(String runId, Supplier<AgentSession> supplier) {
        SessionEntry entry = sessions.get(runId);
        if (entry == null) {
            // 创建在锁外执行，不占用桶锁
            SessionEntry created = new SessionEntry(supplier.get());
            SessionEntry winner = sessions.putIfAbsent(runId, created);
            entry = winner != null ? winner : created;
        }
        entry.lastAccessAt = System.currentTimeMillis();
        return entry.session;
    }

    /**
     * 查找已存在的会话；不存在返回 null 而不是抛 {@link SessionNotFoundException}。
     * 适用于“先探测、未命中再创建”的场景。
     */
    public AgentSession getOrNull(String runId) {
        if (runId == null) {
            return null;
        }
        SessionEntry entry = sessions.get(runId);
        return entry == null ? null : entry.session;
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
     *
     * @return 被淘汰的 runId，调用方需据此清理 AgentContext 等关联状态
     */
    public Set<String> evictIdle(Duration maxIdle) {
        long threshold = System.currentTimeMillis() - maxIdle.toMillis();
        Set<String> evicted = new LinkedHashSet<>();
        sessions.entrySet().removeIf(entry -> {
            SessionEntry value = entry.getValue();
            boolean stale = value.lastAccessAt < threshold
                    && value.session.getStatus() == AgentSession.Status.FREE;
            if (stale) {
                evicted.add(entry.getKey());
            }
            return stale;
        });
        return evicted;
    }

    /**
     * 放弃等待人工审批超时的会话（清理审批上下文并置回 FREE）。
     *
     * @return 被放弃审批的 runId
     */
    public Set<String> abandonExpiredApprovalWaits(Duration maxWait) {
        Set<String> abandoned = new LinkedHashSet<>();
        sessions.forEach((runId, entry) -> {
            if (entry.session.abandonStaleApproval(maxWait)) {
                abandoned.add(runId);
            }
        });
        return abandoned;
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
