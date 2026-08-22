package com.agentcode.context;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class AgentContextProjectContextTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldLoadProjectContextFromKamaContextMd() throws Exception {
        Path kamaDir = tempDir.resolve(".kama");
        Files.createDirectories(kamaDir);
        Files.writeString(kamaDir.resolve("context.md"), "# Java 项目约定\n只允许在 workspace 下创建文件");

        AgentContext context = AgentContext.builder()
                .runId("run")
                .workspace(tempDir.toString())
                .build();

        String prompt = context.systemPrompt("BASE");

        assertThat(prompt)
                .contains("## Project Context")
                .contains("只允许在 workspace 下创建文件");
    }

    @Test
    void shouldFallbackToAgentMdWhenKamaContextMissing() throws Exception {
        Files.writeString(tempDir.resolve("AGENT.md"), "# AGENT.md 项目指引");

        AgentContext context = AgentContext.builder()
                .runId("run")
                .workspace(tempDir.toString())
                .build();

        String prompt = context.systemPrompt("BASE");

        assertThat(prompt)
                .contains("## Project Context")
                .contains("AGENT.md 项目指引");
    }

    @Test
    void shouldReturnBaseOnlyWhenNoProjectContextExists() {
        AgentContext context = AgentContext.builder()
                .runId("run")
                .workspace(tempDir.toString())
                .build();

        assertThat(context.systemPrompt("BASE")).isEqualTo("BASE");
    }

    @Test
    void shouldIgnoreBlankWorkspace() {
        AgentContext context = AgentContext.builder()
                .runId("run")
                .workspace("   ")
                .build();

        assertThat(context.systemPrompt("BASE")).isEqualTo("BASE");
    }
}
