package com.agentcode.registry;

import com.agentcode.session.AgentSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 会话注册表的回收语义：evictIdle 要返回被淘汰的 runId（调用方需连带清理 AgentContext），
 * 并且不能动还在运行或等待审批的会话。
 */
class AgentSessionRegistryTest {

    private AgentSessionRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new AgentSessionRegistry();
    }

    @Test
    void shouldEvictIdleFreeSessionsAndReportIds() {
        registry.getOrCreate("free-run", () -> session(AgentSession.Status.FREE));
        registry.getOrCreate("running-run", () -> session(AgentSession.Status.RUNNING));
        registry.getOrCreate("waiting-run", () -> session(AgentSession.Status.INTERRUPTED));

        Set<String> evicted = registry.evictIdle(Duration.ZERO);

        assertThat(evicted).containsExactly("free-run");
        assertThat(registry.size()).isEqualTo(2);
        assertThatThrownBy(() -> registry.get("free-run"))
                .isInstanceOf(com.agentcode.exception.SessionNotFoundException.class);
    }

    @Test
    void shouldKeepRecentlyAccessedSessions() {
        registry.getOrCreate("fresh", () -> session(AgentSession.Status.FREE));

        assertThat(registry.evictIdle(Duration.ofMinutes(30))).isEmpty();
        assertThat(registry.size()).isEqualTo(1);
    }

    @Test
    void shouldAbandonExpiredApprovalWaits() {
        AgentSession waiting = session(AgentSession.Status.INTERRUPTED);
        when(waiting.abandonStaleApproval(Duration.ofMinutes(10))).thenReturn(true);
        registry.getOrCreate("waiting", () -> waiting);

        assertThat(registry.abandonExpiredApprovalWaits(Duration.ofMinutes(10)))
                .containsExactly("waiting");
        verify(waiting).abandonStaleApproval(Duration.ofMinutes(10));
    }

    @Test
    void snapshotShouldExposeCurrentSessions() {
        registry.getOrCreate("a", () -> session(AgentSession.Status.FREE));

        assertThat(registry.snapshot()).containsKey("a");
    }

    private AgentSession session(AgentSession.Status status) {
        AgentSession session = mock(AgentSession.class);
        when(session.getStatus()).thenReturn(status);
        return session;
    }
}
