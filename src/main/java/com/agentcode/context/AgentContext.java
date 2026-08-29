package com.agentcode.context;

import lombok.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
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
    String workspace;

    /**
     * 全局上下文文件（用户级，支持 ~ 前缀）。
     * 由 {@code AgentCodeProperties.Agent#globalContextFile} 注入，为空时不读取。
     */
    String globalContextFile;

    /**
     * 项目上下文文件候选列表（相对 workspace），按顺序取第一个非空文件。
     * 由 {@code AgentCodeProperties.Agent#projectContextFile} 注入到列表首位，
     * 后面保留 .kama/context.md 与 AGENT.md/CLAUDE.md/SOUL.md 兜底。
     */
    @Builder.Default
    List<String> projectContextFiles = new ArrayList<>(DEFAULT_PROJECT_CONTEXT_FILES);

    @Builder.Default
    StringBuilder sessionNotes = new StringBuilder(); // 会话笔记, 用于记录用户在本次会话中的主要要求或关键的短期记忆, 防止遗忘, 此字段应移入数据库存储或由本地持久化

    /** 未配置时使用的项目上下文文件顺序 */
    public static final List<String> DEFAULT_PROJECT_CONTEXT_FILES = List.of(
            ".kama/context.md", "AGENT.md", "CLAUDE.md", "SOUL.md");

    public String systemPrompt(String systemPrompt) {
        StringBuilder sb = new StringBuilder(systemPrompt == null ? "" : systemPrompt);

        String globalContext = parseGlobalContext();
        if (!globalContext.isBlank()) {
            sb.append("\n\n## Global Context\n").append(globalContext);
        }

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
     * 解析用户级全局上下文文件，支持 {@code ~/} 前缀。
     *
     * @return 文件内容；未配置或文件不存在时返回空字符串
     */
    private String parseGlobalContext() {
        if (globalContextFile == null || globalContextFile.isBlank()) {
            return "";
        }
        return readFileIfExists(expandHome(globalContextFile));
    }

    /**
     * 解析项目的提示词：按 {@link #projectContextFiles} 给定的候选顺序依次读取，
     * 默认优先 workspace/.kama/context.md，不存在时回退到 AGENT.md、CLAUDE.md、SOUL.md。
     *
     * @param workspace 项目工作目录
     * @return 项目上下文内容；文件不存在或内容为空时返回空字符串
     */
    private String parseProjectContext(String workspace) {
        if (workspace == null || workspace.isBlank()) {
            return "";
        }
        Path basePath = Paths.get(workspace).toAbsolutePath().normalize();

        List<String> candidates = projectContextFiles == null || projectContextFiles.isEmpty()
                ? DEFAULT_PROJECT_CONTEXT_FILES
                : projectContextFiles;

        for (String fileName : candidates) {
            String content = readFileIfExists(basePath.resolve(fileName));
            if (!content.isBlank()) {
                return content;
            }
        }

        return "";
    }

    /**
     * 把 {@code ~/xxx} 展开成用户主目录下的路径，其他路径原样返回。
     */
    private Path expandHome(String file) {
        if (file.startsWith("~/") || file.equals("~")) {
            String home = System.getProperty("user.home");
            return Paths.get(home).resolve(file.substring(Math.min(2, file.length())));
        }
        return Paths.get(file);
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
