package com.agentcode.dto;

import com.agentcode.common.ShellParseHelper;
import com.agentcode.agent.context.AgentContext;
import com.alibaba.cloud.ai.graph.action.InterruptionMetadata;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

/**
 * 工具审批策略与会话级审批缓存。
 *
 * 负责：
 * - shell/文件工具的静态安全检查（checkCommandValid / checkPathValid）
 * - 会话内已放行命令的缓存（approveCommandForSession / approvePatternForSession）
 * - 审批反馈序列化与参数处理
 */
public class AgentApprovalManager {

    // 检测 bash 命令是否操作 cwd 之外路径的正则规则列表（强制触发 ASK，不可被 allow 名单绕过）
    private static final List<Pattern> OUTSIDE_CWD_PATTERNS = List.of(
            Pattern.compile("(^|\\s)/[^\\s]"),              // absolute path
            Pattern.compile("(^|\\s)~"),                    // tilde home
            Pattern.compile("(^|\\s)\\.\\.(/|$|\\s)"),      // parent traversal
            Pattern.compile("\\$\\{?HOME\\b"),              // $HOME variable
            Pattern.compile("\\$\\{?PWD\\b"),               // $PWD variable
            Pattern.compile("(^|\\s|;|&&|\\|\\|)cd(\\s|$)") // explicit cd
    );

    /**
     * 段内出现这些字符，说明"这条命令不止表面那么简单"：重定向能把只读命令变成写文件，
     * 命令替换与变量展开能把安全命令名当壳子跑别的命令，& 能把命令丢到后台。
     * 命中就不再自动放行，交给人工审批。
     */
    private static final Pattern SHELL_META_PATTERN = Pattern.compile("[<>`&$()]");

    private final AgentContext agentContext;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // 当前会话已放行的命令缓存：精确命令 + 通配符模式（仅内存，不持久化）
    private final Set<String> sessionApprovedCommands = ConcurrentHashMap.newKeySet();
    private final List<Pattern> sessionApprovedPatterns = new CopyOnWriteArrayList<>();

    public AgentApprovalManager(AgentContext agentContext) {
        this.agentContext = agentContext;
    }

    /**
     * 从 pendingInterruption 中按 toolCallId 找到原始反馈
     */
    public InterruptionMetadata.ToolFeedback findFeedback(InterruptionMetadata pendingInterruption, String toolCallId) {
        if (pendingInterruption == null || toolCallId == null) {
            return null;
        }
        return pendingInterruption.toolFeedbacks().stream()
                .filter(f -> toolCallId.equals(f.getId()))
                .findFirst()
                .orElse(null);
    }

    /**
     * EDITED 使用前端传回的新参数，其他情况沿用原始参数
     */
    public String resolveArguments(AgentInterruptHandle handle, String originalArguments) {
        if (handle.getDecision() == AgentInterruptHandle.Decision.EDITED
                && handle.getArguments() != null
                && !handle.getArguments().isBlank()) {
            return handle.getArguments();
        }
        return originalArguments;
    }

    /**
     * APPROVE_ALL：当前会话放行该命令，后续相同命令不再审批。
     *
     * @return 审计文案：缓存了什么、或为什么没缓存
     */
    public String rememberApproval(AgentInterruptHandle handle, String originalArguments) {
        if (handle.getName() == null || !handle.getName().equalsIgnoreCase("shell")) {
            return "not-applicable(非 shell 工具，审批缓存不适用)";
        }
        try {
            String command = ShellParseHelper.extractShellCommand(originalArguments);
            if (command != null && !command.isBlank()) {
                approveCommandForSession(command);
                return command;
            }
            return "not-cached(命令解析为空)";
        } catch (Exception ignored) {
            // 参数解析失败时不缓存，避免错误放行
            return "not-cached(参数解析失败)";
        }
    }

    /**
     * 将待审批的工具反馈列表序列化为前端可读的 JSON
     */
    public String toPermissionJson(List<InterruptionMetadata.ToolFeedback> feedbacks) {
        List<Map<String, String>> items = new ArrayList<>();
        for (InterruptionMetadata.ToolFeedback feedback : feedbacks) {
            Map<String, String> item = new HashMap<>();
            item.put("toolCallId", feedback.getId());
            item.put("toolName", feedback.getName());
            item.put("arguments", feedback.getArguments());
            item.put("description", feedback.getDescription());
            items.add(item);
        }
        try {
            return objectMapper.writeValueAsString(items);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }

    /**
     * 检查 Shell 参数：解析 command 后交给静态评估
     * @param feedback
     * @return true 表示静态评估通过（可自动放行），false 表示需要人工审批/拒绝
     */
    public boolean checkShellValid(InterruptionMetadata.ToolFeedback feedback) {
        return checkCommandValid(ShellParseHelper.extractShellCommand(feedback.getArguments()));
    }

    /**
     * bash 命令静态评估：返回是否允许自动放行。
     * 移植自 Python 分支 permission/policy.py：
     * deny_patterns → outside-cwd 强制 ASK → allow_patterns → default(ASK)
     */
    public boolean checkCommandValid(String command) {
        if (command == null || command.isBlank()) {
            return false;
        }

        // 复合命令（| ; && ||）拆成多个子命令，只要有一个不满足就整体不自动放行
        for (String segment : ShellParseHelper.splitShellSegments(command)) {
            if (!checkSingleCommandValid(segment)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 单条命令的静态评估：命中黑名单/越界则 false，命中安全名单则 true，否则默认 false
     */
    private boolean checkSingleCommandValid(String segment) {
        if (segment == null || segment.isBlank()) {
            return false;
        }

        // outside-cwd 强制 ASK，不允许被安全名单绕过
        if (matchesOutsideCwd(segment)) {
            return false;
        }

        // 重定向 / 命令替换 / 变量展开 / 后台符：同样不能被安全名单绕过
        if (SHELL_META_PATTERN.matcher(segment).find()) {
            return false;
        }

        List<String> tokens = ShellParseHelper.splitCommand(segment);
        if (tokens.isEmpty()) {
            return false;
        }
        String commandName = tokens.get(0);

        // deny_patterns：危险命令黑名单，命中不允许自动放行
        if (ShellParseHelper.DANGEROUS_COMMANDS.contains(commandName)) {
            return false;
        }

        // allow_patterns：安全命令名单，命中自动放行
        if (ShellParseHelper.safeCommands.contains(commandName)) {
            return true;
        }

        // 默认策略：bash 默认 ASK，未命中任何名单时交给人工审批
        return false;
    }

    /**
     * 判断命令是否命中 outside-cwd 启发式规则
     */
    private boolean matchesOutsideCwd(String command) {
        for (Pattern pattern : OUTSIDE_CWD_PATTERNS) {
            if (pattern.matcher(command).find()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断命令是否已在当前会话中被批准（精确命令或通配符模式）
     */
    public boolean isSessionApproved(String command) {
        if (command == null || command.isBlank()) {
            return false;
        }
        String normalized = command.trim();
        if (sessionApprovedCommands.contains(normalized)) {
            return true;
        }
        for (Pattern pattern : sessionApprovedPatterns) {
            if (pattern.matcher(normalized).matches()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 记录当前会话放行一条具体命令
     */
    public void approveCommandForSession(String command) {
        if (command == null || command.isBlank()) {
            return;
        }
        sessionApprovedCommands.add(command.trim());
    }

    /**
     * 记录当前会话放行一类命令（shell 通配符，* 匹配任意串，? 匹配单个字符）
     */
    public void approvePatternForSession(String pattern) {
        if (pattern == null || pattern.isBlank()) {
            return;
        }
        sessionApprovedPatterns.add(ShellParseHelper.compileShellWildcard(pattern.trim()));
    }

    /**
     * 检查文件工具参数中的路径是否仍位于工作区内。
     *
     * <p>参数名按工具真实 schema 取：{@code FileSystemTools} 的方法参数是 {@code filePath}，
     * 旧写法 {@code filepath} 一并兼容 —— 只认其中一个的话，另一个 key 的请求会恒判"区外"，
     * 校验器等于没在工作。
     *
     * <p>除了 {@code normalize()} 比前缀，路径（或父目录）存在时还要按 {@code toRealPath()} 再判一次，
     * 否则工作区里一条指向外面的软链就能把写入带出去。
     */
    public boolean checkPathValid(String argumentsJson) {
        String filePath = extractFilePath(argumentsJson);
        if (filePath.isBlank()) {
            return false;
        }
        Path basePath = Paths.get(agentContext.getWorkspace()).toAbsolutePath().normalize();
        Path normalizedPath;
        try {
            normalizedPath = basePath.resolve(filePath).normalize();
        } catch (InvalidPathException e) {
            // 空字节之类的非法路径：直接不放行
            return false;
        }
        if (!normalizedPath.startsWith(basePath)) {
            return false;
        }
        try {
            Path probe = Files.exists(normalizedPath) ? normalizedPath : normalizedPath.getParent();
            if (probe != null && Files.exists(probe)) {
                return probe.toRealPath().startsWith(basePath.toRealPath());
            }
        } catch (IOException e) {
            // 解析不出真实路径（权限/断链）时按不放行处理
            return false;
        }
        return true;
    }

    /** 从工具参数 JSON 取路径；解析失败或类型不对返回空串（调用方按不放行处理） */
    private String extractFilePath(String argumentsJson) {
        try {
            JsonNode root = objectMapper.readTree(argumentsJson);
            for (String key : List.of("filePath", "filepath", "file_path", "path")) {
                JsonNode value = root.path(key);
                if (value.isTextual() && !value.asText().isBlank()) {
                    return value.asText();
                }
            }
        } catch (Exception ignored) {
            // 解析失败按空路径处理
        }
        return "";
    }
}
