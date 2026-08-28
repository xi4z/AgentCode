package com.agentcode.factory;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * AgentSession 构建参数。
 *
 * <p>approvalTools 显式传入时优先（含传空列表表示"全部免审批"）；
 * 未传入时使用 {@link #DEFAULT_APPROVAL_TOOLS}，服务端装配路径会先读
 * {@code agentcode.agent.approval-tools} 配置再落到该默认值。
 */
@Getter
@Builder
public class SessionBuildOptions {

    /** 内置默认需要人工审批的工具（edit_file 与 write_file 同级，不能绕过审批改文件） */
    public static final List<String> DEFAULT_APPROVAL_TOOLS = List.of("shell", "write_file", "edit_file");

    @Builder.Default
    private final List<String> approvalTools = DEFAULT_APPROVAL_TOOLS;

    private final String systemPrompt;
}
