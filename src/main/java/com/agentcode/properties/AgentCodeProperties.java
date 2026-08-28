package com.agentcode.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * agentcode 配置绑定。
 *
 * <p>字段层级必须与 application.yml 保持一致：
 * <pre>
 * agentcode:
 *   audit:
 *     enabled: true
 *   agent:
 *     max-steps: 20
 *     system-prompt: ...
 *     global-context-file: ~/.agent/context.md
 *     project-context-file: .agent/context.md
 * </pre>
 */
@ConfigurationProperties(prefix = "agentcode")
@Data
public class AgentCodeProperties {

    private Audit audit = new Audit();

    private Agent agent = new Agent();

    @Data
    public static class Audit {
        /** 是否包装 ChatModel 输出 AI 调用审计日志 */
        private boolean enabled = true;
    }

    @Data
    public static class Agent {
        /** 单次 run 允许的最大模型调用次数 */
        private int maxSteps = 20;

        /** 基础系统提示词，为空时只拼接上下文 */
        private String systemPrompt;

        /** 全局上下文文件（支持 ~ 前缀），不存在时忽略 */
        private String globalContextFile = "~/.agent/context.md";

        /** 项目上下文文件（相对 workspace），不存在时回退到 AGENT.md/CLAUDE.md/SOUL.md */
        private String projectContextFile = ".agent/context.md";
    }
}
