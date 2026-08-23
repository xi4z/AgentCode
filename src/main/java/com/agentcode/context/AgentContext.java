package com.agentcode.context;

import lombok.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

@Data
@Builder
public class AgentContext {
    /**
     * AgentContext 流程:
     * 在从本地持久化或数据库拿到数据后: AgentContextModel
     * 注入 systemPrompt, globalContext 与 projectContext
     * sessionNotes , workspace 由 model 注入
     */

    final String runId;
    String goal;
    final String workspace;

    StringBuilder sessionNotes; // 会话笔记, 用于记录用户在本次会话中的主要要求或关键的短期记忆, 防止遗忘, 此字段应移入数据库存储或由本地持久化

    public String systemPrompt(String systemPrompt) {
        StringBuilder sb = new StringBuilder(systemPrompt == null ? "" : systemPrompt);
        String projectContext = parseProjectContext(workspace);
        if (projectContext != null && !projectContext.isBlank()) {
            sb.append("\n\n## Project Context\n").append(projectContext);
        }

        if (sessionNotes != null && !sessionNotes.isEmpty()) {
            sb.append("\n\n## Session Notes\n").append(sessionNotes);
        }
        return sb.toString();
    }

    /**
     * 解析项目的提示词：优先读取 workspace/.kama/context.md，
     * 不存在时依次回退到 AGENT.md、CLAUDE.md、SOUL.md。
     *
     * @param workspace 项目工作目录
     * @return 项目上下文内容；文件不存在或内容为空时返回空字符串
     */
    private String parseProjectContext(String workspace) {
        if (workspace == null || workspace.isBlank()) {
            return "";
        }
        Path basePath = Paths.get(workspace).toAbsolutePath().normalize();

        Path contextFile = basePath.resolve(".kama").resolve("context.md");
        String content = readFileIfExists(contextFile);
        if (!content.isBlank()) {
            return content;
        }

        for (String fileName : List.of("AGENT.md", "CLAUDE.md", "SOUL.md")) {
            content = readFileIfExists(basePath.resolve(fileName));
            if (!content.isBlank()) {
                return content;
            }
        }

        return "";
    }

    private String readFileIfExists(Path path) {
        if (path == null || !Files.exists(path)) {
            return "";
        }
        try {
            return Files.readString(path).strip();
        } catch (Exception e) {
            // 文件不可读时静默忽略，避免把 IO 错误带入 prompt
            return "";
        }
    }
}
