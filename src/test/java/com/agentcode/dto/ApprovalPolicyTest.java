package com.agentcode.dto;

import com.agentcode.properties.AgentCodeProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 审批策略：默认名单 + 可由 agentcode.agent.approval.* 覆盖的白/黑名单。
 */
class ApprovalPolicyTest {

    @Test
    void defaultsShouldAutoApproveSafeCommandsButNotOutsideCwd() {
        ApprovalPolicy policy = ApprovalPolicy.defaults();

        assertThat(policy.autoApproves("ls -la")).isTrue();
        assertThat(policy.autoApproves("cat /etc/hosts")).isFalse();     // 绝对路径 → 越界
        assertThat(policy.autoApproves("cd /tmp")).isFalse();            // 显式 cd → 越界
        assertThat(policy.autoApproves("rm -rf target")).isFalse();      // 危险命令
        assertThat(policy.autoApproves("git commit -m x")).isFalse();    // 未命中白名单 → 默认人工
        assertThat(policy.autoApproves("ls -la | cat")).isTrue();        // 复合命令全部安全
        assertThat(policy.autoApproves("ls && rm -rf target")).isFalse(); // 复合命令含危险段
    }

    @Test
    void allowPatternsShouldMatchWholeSegmentOnly() {
        ApprovalPolicy policy = ApprovalPolicy.from(approval(
                List.of("git status*"), List.of()));

        assertThat(policy.autoApproves("git status -sb")).isTrue();
        // 整条子命令匹配：允许 "git status*" 不应该放行以其他命令开头的同款参数
        assertThat(policy.autoApproves("sudo git status")).isFalse();
    }

    @Test
    void allowPatternsMustNotBypassOutsideCwd() {
        ApprovalPolicy policy = ApprovalPolicy.from(approval(List.of("cat *"), List.of()));

        assertThat(policy.autoApproves("cat notes.md")).isTrue();
        assertThat(policy.autoApproves("cat /etc/shadow")).isFalse();
    }

    @Test
    void denyPatternsBeatAllowPatterns() {
        ApprovalPolicy policy = ApprovalPolicy.from(approval(
                List.of("git *"), List.of("git push*")));

        assertThat(policy.autoApproves("git status")).isTrue();
        assertThat(policy.autoApproves("git push origin main")).isFalse();
    }

    @Test
    void configuredSafeCommandsReplaceBuiltinList() {
        AgentCodeProperties.Approval config = new AgentCodeProperties.Approval();
        config.setSafeCommands(List.of("git"));
        ApprovalPolicy policy = ApprovalPolicy.from(config);

        assertThat(policy.autoApproves("git status")).isTrue();
        assertThat(policy.safeCommands()).containsExactly("git");
        // 内置的 ls 不再放行
        assertThat(policy.autoApproves("ls")).isFalse();
    }

    @Test
    void isDeniedShouldLimitSessionLevelApproval() {
        ApprovalPolicy policy = ApprovalPolicy.from(approval(
                List.of(), List.of("git push*")));

        assertThat(policy.isDenied("git push origin main")).isTrue();
        assertThat(policy.isDenied("rm -rf build")).isTrue();
        // 越界只是"默认询问"的理由，不属于硬拒绝：用户 APPROVE_ALL 之后应当生效
        assertThat(policy.isDenied("cat /etc/hosts")).isFalse();
        assertThat(policy.isDenied("ls -la")).isFalse();
    }

    private AgentCodeProperties.Approval approval(List<String> allow, List<String> deny) {
        AgentCodeProperties.Approval config = new AgentCodeProperties.Approval();
        config.setAllowPatterns(allow);
        config.setDenyPatterns(deny);
        return config;
    }
}
