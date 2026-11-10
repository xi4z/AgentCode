package com.agentcode.agent;

import com.agentcode.common.ShellParseHelper;

import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Shell 审批策略：安全命令自动放行，越界路径与危险命令一律交给人工审批。
 *
 * <p>复合命令按 {@code |}、{@code ;}、{@code &&}、{@code ||} 拆分后逐段判断；只要有一段不满足，
 * 整条命令就需要审批。
 */
public final class ShellPolicy {

    // 这些模式命中时直接要求审批，优先级高于安全命令名单。
    private static final List<Pattern> OUTSIDE_WORKSPACE_PATTERNS =
            List.of(
                    Pattern.compile("(^|\\s)/[^\\s]"),
                    Pattern.compile("(^|\\s)~"),
                    Pattern.compile("(^|\\s)\\.\\.(/|$|\\s)"),
                    Pattern.compile("\\$\\{?HOME\\b"),
                    Pattern.compile("\\$\\{?PWD\\b"),
                    Pattern.compile("(^|\\s|;|&&|\\|\\|)cd(\\s|$)"));

    public boolean autoApprovable(String command) {
        if (command == null || command.isBlank()) {
            return false;
        }
        // 复合命令逐段检查，避免 "echo ok && rm -rf" 借安全命令前缀绕过。
        for (String segment : ShellParseHelper.splitShellSegments(command)) {
            if (!singleSegmentApprovable(segment)) {
                return false;
            }
        }
        return true;
    }

    private boolean singleSegmentApprovable(String segment) {
        if (segment == null || segment.isBlank() || matchesOutsideWorkspace(segment)) {
            return false;
        }
        List<String> tokens = ShellParseHelper.splitCommand(segment);
        if (tokens.isEmpty()) {
            return false;
        }
        String commandName = tokens.get(0);
        if (ShellParseHelper.DANGEROUS_COMMANDS.contains(commandName)) {
            return false;
        }
        return ShellParseHelper.safeCommands.contains(commandName);
    }

    private boolean matchesOutsideWorkspace(String command) {
        for (Pattern pattern : OUTSIDE_WORKSPACE_PATTERNS) {
            if (pattern.matcher(command).find()) {
                return true;
            }
        }
        return false;
    }

    public Path resolveWorkingDirectory(Path workspace, String workingDirectory) {
        if (workingDirectory == null || workingDirectory.isBlank()) {
            return workspace;
        }
        // 解析后必须仍在工作区内，防止 ../ 或绝对路径逃逸。
        Path resolved = workspace.resolve(workingDirectory).normalize();
        if (!resolved.startsWith(workspace)) {
            throw new IllegalArgumentException(
                    "working_directory 必须位于工作区内: " + workingDirectory);
        }
        return resolved;
    }
}
