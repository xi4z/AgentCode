package com.agentcode.dto;

import com.agentcode.common.ShellParseHelper;
import com.agentcode.agent.AgentContext;
import com.alibaba.cloud.ai.graph.action.InterruptionMetadata;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具审批与会话级审批缓存。
 *
 * 负责：
 * - shell/文件工具的静态安全检查（策略委托给 {@link ApprovalPolicy}，可由配置覆盖）
 * - 会话内已放行命令的缓存（来自用户 APPROVE_ALL 的精确命令）
 * - 审批反馈序列化与参数处理
 */
public class AgentApprovalManager {

    /** 文件工具参数中可能出现的绝对路径键（框架实际使用 file_path） */
    private static final List<String> FILE_PATH_KEYS = List.of(
            "file_path", "filePath", "filepath", "path", "target_file", "notebook_path");

    private final AgentContext agentContext;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ApprovalPolicy policy;

    // 当前会话已放行的命令缓存（仅内存，不持久化）
    private final Set<String> sessionApprovedCommands = ConcurrentHashMap.newKeySet();

    public AgentApprovalManager(AgentContext agentContext) {
        this(agentContext, ApprovalPolicy.defaults());
    }

    public AgentApprovalManager(AgentContext agentContext, ApprovalPolicy policy) {
        this.agentContext = agentContext;
        this.policy = policy == null ? ApprovalPolicy.defaults() : policy;
    }

    /** 当前生效的审批策略（便于审计与测试观察） */
    public ApprovalPolicy policy() {
        return policy;
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
     * APPROVE_ALL：当前会话放行该命令，后续相同命令不再审批
     */
    public void rememberApproval(AgentInterruptHandle handle, String originalArguments) {
        if (handle.getName() == null || !handle.getName().equalsIgnoreCase("shell")) {
            return;
        }
        try {
            String command = ShellParseHelper.extractShellCommand(originalArguments);
            if (command != null && !command.isBlank()) {
                approveCommandForSession(command);
            }
        } catch (Exception ignored) {
            // 参数解析失败时不缓存，避免错误放行
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
     * bash 命令静态评估：是否允许自动放行。评估规则见 {@link ApprovalPolicy}
     * （deny → outside-cwd 强制人工 → allow → 默认人工），名单可由 agentcode.agent.approval.* 覆盖。
     */
    public boolean checkCommandValid(String command) {
        return policy.autoApproves(command);
    }

    /**
     * 判断命令是否已在当前会话中被批准（来自用户 APPROVE_ALL 的精确命令）。
     *
     * <p>会话级放行不能覆盖 deny/危险命令：即使用户曾经一律批准，命中黑名单时仍然询问。
     */
    public boolean isSessionApproved(String command) {
        if (command == null || command.isBlank()) {
            return false;
        }
        if (policy.isDenied(command)) {
            return false;
        }
        return sessionApprovedCommands.contains(command.trim());
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
     * 检查文件工具参数中的路径是否仍位于工作区内。
     *
     * <p>Spring AI Alibaba 的文件工具（write_file / edit_file / read_file）用
     * {@code @JsonProperty("file_path")} 暴露参数，因此这里必须按候选键取值；
     * 解析失败或无法判定时返回 false（交人工审批），不再抛异常打断事件流。
     */
    public boolean checkPathValid(String arguments) {
        String filePath = extractFilePath(arguments);
        if (filePath == null || filePath.isBlank()) {
            return false;
        }
        Path basePath = workspaceRoot();
        try {
            Path normalizedPath = basePath.resolve(filePath).normalize();
            return normalizedPath.startsWith(basePath);
        } catch (Exception e) {
            // 非法路径（含空字节等）一律不自动放行
            return false;
        }
    }

    /**
     * 从工具参数 JSON 中取出文件路径，兼容不同工具/版本的键名。
     *
     * @return 路径字符串；无法解析时返回 null
     */
    public String extractFilePath(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(arguments);
            for (String key : FILE_PATH_KEYS) {
                JsonNode node = root.get(key);
                if (node != null && node.isTextual() && !node.asText().isBlank()) {
                    return node.asText();
                }
            }
            return null;
        } catch (Exception e) {
            // 参数不是合法 JSON：不抛给上层流，改为继续走人工审批
            return null;
        }
    }

    private Path workspaceRoot() {
        String workspace = agentContext == null ? null : agentContext.getWorkspace();
        if (workspace == null || workspace.isBlank()) {
            workspace = System.getProperty("user.dir");
        }
        return Paths.get(workspace).toAbsolutePath().normalize();
    }

    /**
     * 把尚未答复的审批项 id 序列化为 JSON 数组，供前端提示“还欠几个决定”。
     */
    public String toPendingIdsJson(Collection<String> ids) {
        try {
            return objectMapper.writeValueAsString(ids == null ? List.of() : new ArrayList<>(ids));
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }
}
