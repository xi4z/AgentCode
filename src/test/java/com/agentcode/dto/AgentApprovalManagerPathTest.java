package com.agentcode.dto;

import com.agentcode.common.ShellParseHelper;
import com.agentcode.context.AgentContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 回归测试：文件/命令参数解析必须匹配框架真实 schema，并且不能把解析异常抛进事件流。
 *
 * Spring AI Alibaba 的 write_file / edit_file / read_file 用 @JsonProperty("file_path")
 * 暴露参数，旧实现只认 "filepath"，导致工作区内的写操作永远无法自动放行；
 * 参数非法时还会抛 RuntimeException 让整轮 run 以 error 结束。
 */
class AgentApprovalManagerPathTest {

    @TempDir
    Path workspace;

    @Test
    void shouldAcceptWorkspaceRelativeAndAbsoluteFilePath() {
        AgentApprovalManager manager = manager();

        assertThat(manager.checkPathValid("{\"file_path\":\"notes/a.md\",\"content\":\"x\"}")).isTrue();
        assertThat(manager.checkPathValid("{\"file_path\":\"" + workspace.resolve("a.md") + "\"}")).isTrue();
    }

    @Test
    void shouldSupportLegacyAndAlternatePathKeys() {
        AgentApprovalManager manager = manager();

        assertThat(manager.extractFilePath("{\"filepath\":\"a.md\"}")).isEqualTo("a.md");
        assertThat(manager.extractFilePath("{\"path\":\"a.md\"}")).isEqualTo("a.md");
        assertThat(manager.extractFilePath("{\"file_path\":\"a.md\"}")).isEqualTo("a.md");
    }

    @Test
    void shouldRejectPathsOutsideWorkspace() {
        AgentApprovalManager manager = manager();

        assertThat(manager.checkPathValid("{\"file_path\":\"/etc/passwd\"}")).isFalse();
        assertThat(manager.checkPathValid("{\"file_path\":\"../outside.md\"}")).isFalse();
    }

    @Test
    void shouldNotThrowOnMalformedArguments() {
        AgentApprovalManager manager = manager();

        assertThatCode(() -> manager.checkPathValid("not-json")).doesNotThrowAnyException();
        assertThatCode(() -> manager.checkPathValid(null)).doesNotThrowAnyException();
        assertThatCode(() -> manager.checkPathValid("{}")).doesNotThrowAnyException();
        assertThat(manager.checkPathValid("not-json")).isFalse();
        assertThat(manager.checkPathValid("{}")).isFalse();
    }

    @Test
    void shouldReturnNullCommandOnMalformedShellArguments() {
        assertThat(ShellParseHelper.extractShellCommand("not-json")).isNull();
        assertThat(ShellParseHelper.extractShellCommand(null)).isNull();
        assertThat(ShellParseHelper.extractShellCommand("{}")).isNull();
        assertThat(ShellParseHelper.extractShellCommand("{\"command\":\"ls -la\"}")).isEqualTo("ls -la");
    }

    @Test
    void malformedShellArgumentsShouldNotAutoApprove() {
        AgentApprovalManager manager = manager();

        assertThat(manager.checkCommandValid(ShellParseHelper.extractShellCommand("not-json"))).isFalse();
    }

    private AgentApprovalManager manager() {
        AgentContext context = AgentContext.builder()
                .runId("approval-path-test")
                .workspace(workspace.toString())
                .build();
        return new AgentApprovalManager(context);
    }
}
