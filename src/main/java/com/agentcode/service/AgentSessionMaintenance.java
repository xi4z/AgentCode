package com.agentcode.service;

import com.agentcode.properties.AgentCodeProperties;
import com.agentcode.registry.AgentSessionRegistry;
import com.agentcode.store.AgentContextStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Set;

/**
 * 会话生命周期治理：
 * <ul>
 *   <li>放弃等待人工审批超时的会话（否则客户端掉线后会永久停在 INTERRUPTED）</li>
 *   <li>回收长时间空闲的 FREE 会话，并连带清理 AgentContext</li>
 * </ul>
 *
 * 间隔与阈值来自 {@code agentcode.agent.session.*}。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentSessionMaintenance {

    private final AgentSessionRegistry sessionRegistry;
    private final AgentContextStore agentContextStore;
    private final AgentCodeProperties agentCodeProperties;

    @Scheduled(
            initialDelayString = "${agentcode.agent.session.evict-interval:1m}",
            fixedDelayString = "${agentcode.agent.session.evict-interval:5m}")
    public void cleanup() {
        AgentCodeProperties.Session config = sessionConfig();
        try {
            Set<String> abandoned = sessionRegistry.abandonExpiredApprovalWaits(config.getApprovalWaitTimeout());
            Set<String> evicted = sessionRegistry.evictIdle(config.getIdleTimeout());
            for (String runId : evicted) {
                agentContextStore.remove(runId);
            }
            if (!abandoned.isEmpty() || !evicted.isEmpty()) {
                log.info("AUDIT_SESSION_CLEANUP abandonedApproval={} evicted={} remainingSessions={}",
                        abandoned, evicted, sessionRegistry.size());
            }
        } catch (Exception e) {
            // 清理任务不能因单次异常中断调度
            log.warn("AUDIT_SESSION_CLEANUP_FAILED error={}", e.getMessage());
        }
    }

    private AgentCodeProperties.Session sessionConfig() {
        AgentCodeProperties.Agent agent = agentCodeProperties == null ? null : agentCodeProperties.getAgent();
        AgentCodeProperties.Session session = agent == null ? null : agent.getSession();
        if (session == null) {
            session = new AgentCodeProperties.Session();
        }
        if (session.getIdleTimeout() == null) {
            session.setIdleTimeout(Duration.ofMinutes(30));
        }
        if (session.getApprovalWaitTimeout() == null) {
            session.setApprovalWaitTimeout(Duration.ofMinutes(10));
        }
        return session;
    }
}
