package com.agentcode.dto;

import com.agentcode.context.AgentContext;
import com.agentcode.properties.AgentCodeProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 会话级放行（APPROVE_ALL 缓存）不能盖过黑名单与越界检查。
 */
class AgentApprovalManagerSessionCacheTest {

    @Test
    void sessionApprovalShouldSkipSafeCommandOnly() {
        AgentApprovalManager manager = new AgentApprovalManager(context(), ApprovalPolicy.defaults());

        manager.approveCommandForSession("git commit -m ok");
        assertThat(manager.isSessionApproved("git commit -m ok")).isTrue();
        assertThat(manager.isSessionApproved("git commit -m other")).isFalse();
    }

    @Test
    void sessionApprovalMustNotOverrideDeniedCommands() {
        AgentApprovalManager manager = new AgentApprovalManager(context(), ApprovalPolicy.defaults());

        manager.approveCommandForSession("rm -rf build");
        assertThat(manager.isSessionApproved("rm -rf build"))
                .as("危险命令即使命中会话缓存也要再次询问")
                .isFalse();
    }

    @Test
    void sessionApprovalShouldHonourOutsideCwdCommandTheUserApproved() {
        AgentApprovalManager manager = new AgentApprovalManager(context(), ApprovalPolicy.defaults());

        // 越界命令默认询问；但用户明确 APPROVE_ALL 之后，同一会话内这条命令不应再打扰他
        manager.approveCommandForSession("cat /etc/hosts");
        assertThat(manager.isSessionApproved("cat /etc/hosts")).isTrue();
    }

    @Test
    void sessionApprovalMustNotOverrideDenyPatterns() {
        AgentCodeProperties.Approval approval = new AgentCodeProperties.Approval();
        approval.setDenyPatterns(List.of("git push*"));
        AgentApprovalManager manager = new AgentApprovalManager(
                context(), ApprovalPolicy.from(approval));

        manager.approveCommandForSession("git push origin main");
        assertThat(manager.isSessionApproved("git push origin main")).isFalse();
    }

    @Test
    void singleArgConstructorShouldFallBackToBuiltinPolicy() {
        AgentApprovalManager manager = new AgentApprovalManager(context());

        assertThat(manager.policy()).isNotNull();
        assertThat(manager.checkCommandValid("ls -la")).isTrue();
        assertThat(manager.checkCommandValid("rm -rf build")).isFalse();
    }

    private AgentContext context() {
        return AgentContext.builder()
                .runId("session-cache-test")
                .workspace(System.getProperty("java.io.tmpdir"))
                .build();
    }
}
