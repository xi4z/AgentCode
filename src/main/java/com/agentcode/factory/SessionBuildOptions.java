package com.agentcode.factory;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * AgentSession 构建参数。
 *
 * 默认 approvalTools 与旧 AgentSession 构造器保持一致。
 */
@Getter
@Builder
public class SessionBuildOptions {

    /** 有写/执行副作用的工具必须都在这里，否则它们不受审批门禁管 */
    @Builder.Default
    private final List<String> approvalTools = List.of("shell", "write_file", "edit_file");

    private final String systemPrompt;

    /** 长期记忆开关；null = 用配置（agentcode.memory.enabled） */
    private final Boolean memoryEnabled;

    /** 上下文压缩开关；null = 用配置（agentcode.summarization.enabled） */
    private final Boolean summarizationEnabled;

    /** 压缩触发阈值与保留消息数；null = 用配置 */
    private final Integer summarizationMaxTokens;
    private final Integer summarizationKeepMessages;
}
