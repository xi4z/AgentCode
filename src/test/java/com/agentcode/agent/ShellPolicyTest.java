package com.agentcode.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证安全命令、危险命令与复合命令的自动审批边界。 */
class ShellPolicyTest {

    private final ShellPolicy policy = new ShellPolicy();

    @Test
    void shouldAutoApproveSafeCommandsOnly() {
        assertThat(policy.autoApprovable("echo hello")).isTrue();
        assertThat(policy.autoApprovable("pwd")).isTrue();
        assertThat(policy.autoApprovable("echo hello && ls")).isTrue();
        assertThat(policy.autoApprovable("mkdir demo")).isFalse();
        assertThat(policy.autoApprovable("rm -rf demo")).isFalse();
        assertThat(policy.autoApprovable("cd /tmp")).isFalse();
        assertThat(policy.autoApprovable("echo hi | rm -rf demo")).isFalse();
    }
}
