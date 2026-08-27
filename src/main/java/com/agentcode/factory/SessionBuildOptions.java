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

    @Builder.Default
    private final List<String> approvalTools = List.of("shell", "write_file");

    private final String systemPrompt;
}
