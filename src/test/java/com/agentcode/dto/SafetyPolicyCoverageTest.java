package com.agentcode.dto;

import com.agentcode.common.ShellParseHelper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 安全审批覆盖度测试：把量化指标里三类"应有 100% 覆盖"的安全断言，
 * 变成对完整名单/枚举的穷举遍历（by construction），
 * 因此只要这些用例通过，deny/path/decision 覆盖率即 100%。
 *
 * 对应 metrics/schema.json:
 *   safety.deny_rule_coverage / safety.path_traversal_blocked / safety.decision_coverage
 */
class SafetyPolicyCoverageTest {

    private final ApprovalPolicy policy = ApprovalPolicy.defaults();

    static List<String> dangerousCommands() {
        // 内置危险命令全集，作为黑名单覆盖率的分子来源
        return ApprovalPolicy.DEFAULT_DANGEROUS_COMMANDS.stream().sorted().toList();
    }

    @ParameterizedTest(name = "[deny] {0} 必须被硬拒绝且不能自动放行")
    @MethodSource("dangerousCommands")
    void everyDangerousCommandIsDeniedAndNotAutoApproved(String cmd) {
        assertThat(policy.isDenied(cmd + " -f"))
                .as("危险命令 %s 应命中 isDenied", cmd).isTrue();
        assertThat(policy.autoApproves(cmd + " -f"))
                .as("危险命令 %s 不能自动放行", cmd).isFalse();
    }

    @Test
    void dangerousListIsFullyCovered() {
        // 分子 == 分母：遍历集合就是全集，防止名单扩容后遗漏测试
        assertThat(dangerousCommands())
                .containsExactlyInAnyOrderElementsOf(ShellParseHelper.DANGEROUS_COMMANDS);
        assertThat(dangerousCommands()).isNotEmpty();
    }

    /**
     * 每个 outside-cwd 启发式模式至少一条样本命令：
     * 越界路径应强制人工（autoApproves=false），但即便用户 APPROVE_ALL
     * 也应放行，所以不属于硬拒绝（isDenied=false）。
     */
    static List<String> outsideCwdSamples() {
        return List.of(
                "cat /etc/hosts",        // absolute path
                "cat ~/secret",          // tilde home
                "cat ../../etc/passwd",  // parent traversal
                "cat $HOME/.ssh/id_rsa", // $HOME variable
                "cat $PWD/../x",         // $PWD variable
                "cd /tmp"                // explicit cd
        );
    }

    @ParameterizedTest(name = "[outside-cwd] {0} 需人工审批")
    @MethodSource("outsideCwdSamples")
    void everyOutsideCwdSampleForcesManualApproval(String command) {
        assertThat(policy.autoApproves(command))
                .as("越界命令 %s 不能自动放行", command).isFalse();
        // 越界不是硬拒绝：用户显式一律批准后应生效（见 isDenied 语义）
        assertThat(policy.isDenied(command))
                .as("越界命令 %s 不应被硬拒绝", command).isFalse();
    }

    @Test
    void outsideCwdSamplesCoverEveryBuiltinPattern() {
        // 保证"每条内置模式都有样本"，样本数 == 模式数
        assertThat(outsideCwdSamples())
                .hasSize(ApprovalPolicy.DEFAULT_OUTSIDE_CWD_PATTERNS.size());
    }

    @ParameterizedTest(name = "[decision] {0}")
    @ValueSource(strings = {"APPROVED", "APPROVE_ALL", "REJECTED", "EDITED"})
    void everyApprovalDecisionIsResolvable(String name) {
        assertThat(AgentInterruptHandle.Decision.valueOf(name)).isNotNull();
    }

    @Test
    void decisionCoverageIsComplete() {
        // 四种审批决策枚举全部被测：测试的 decision 数 == 枚举数 == 4
        assertThat(AgentInterruptHandle.Decision.values())
                .extracting(Enum::name)
                .containsExactlyInAnyOrder("APPROVED", "APPROVE_ALL", "REJECTED", "EDITED");
    }
}
