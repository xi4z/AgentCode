package com.agentcode.agent;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 把工作区中的项目说明文件拼进系统提示词。
 *
 * <p>读取顺序固定为 {@code .kama/context.md}、{@code AGENTS.md}、{@code AGENT.md}、
 * {@code CLAUDE.md}、{@code SOUL.md}，命中第一个非空文件后停止。
 */
public final class WorkspacePrompt {

    private static final List<String> FALLBACK_FILES =
            List.of("AGENTS.md", "AGENT.md", "CLAUDE.md", "SOUL.md");

    private WorkspacePrompt() {
    }

    public static String build(String basePrompt, Path workspace) {
        String context = readProjectContext(workspace);
        if (context.isBlank()) {
            return basePrompt;
        }
        return basePrompt + "\n\n## Project Context\n" + context;
    }

    private static String readProjectContext(Path workspace) {
        Path kama = workspace.resolve(".kama").resolve("context.md");
        String content = readIfPresent(kama);
        if (!content.isBlank()) {
            return content;
        }
        for (String name : FALLBACK_FILES) {
            content = readIfPresent(workspace.resolve(name));
            if (!content.isBlank()) {
                return content;
            }
        }
        return "";
    }

    private static String readIfPresent(Path path) {
        if (!Files.isRegularFile(path)) {
            return "";
        }
        try {
            return Files.readString(path).strip();
        } catch (IOException error) {
            throw new UncheckedIOException("无法读取项目上下文文件: " + path, error);
        }
    }
}
