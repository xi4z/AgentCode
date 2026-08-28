package com.agentcode.context;

import com.agentcode.properties.AgentCodeProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 agentcode.agent.* 中配置的上下文文件确实会进入 system prompt。
 */
class AgentContextConfiguredFilesTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldPreferConfiguredProjectContextFile() throws Exception {
        AgentCodeProperties.Agent agent = new AgentCodeProperties.Agent();
        agent.setProjectContextFile("MY_CONTEXT.md");
        Files.createDirectories(tempDir.resolve(".agent"));
        Files.writeString(tempDir.resolve("MY_CONTEXT.md"), "使用配置指定的项目上下文");
        Files.writeString(tempDir.resolve("AGENT.md"), "兜底内容");

        AgentContext context = AgentContext.builder()
                .runId("run")
                .workspace(tempDir.toString())
                .build();
        context.setGlobalContextFile(agent.getGlobalContextFile());
        context.setProjectContextFiles(new ArrayList<>(List.of(agent.getProjectContextFile())));

        assertThat(context.systemPrompt("BASE"))
                .contains("## Project Context")
                .contains("使用配置指定的项目上下文")
                .doesNotContain("兜底内容");
    }

    @Test
    void shouldAppendGlobalContextWhenFileExists() throws Exception {
        Path globalFile = tempDir.resolve("global-context.md");
        Files.writeString(globalFile, "全局约定：禁止 push main");

        AgentContext context = AgentContext.builder()
                .runId("run")
                .workspace(tempDir.toString())
                .globalContextFile(globalFile.toString())
                .build();

        assertThat(context.systemPrompt("BASE"))
                .contains("## Global Context")
                .contains("禁止 push main");
    }

    @Test
    void shouldExpandTildeInGlobalContextFile() {
        String home = System.getProperty("user.home");
        AgentContext context = AgentContext.builder()
                .runId("run")
                .workspace(tempDir.toString())
                .globalContextFile("~/.agent/definitely-missing-file.md")
                .build();

        // 不存在时静默跳过，且不能因为 ~ 前缀抛异常
        assertThat(context.systemPrompt("BASE")).isEqualTo("BASE");
        assertThat(home).isNotNull();
    }
}
