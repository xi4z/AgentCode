package com.agentcode.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

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
 *     approval-tools: [shell, write_file, edit_file]
 *     approval:
 *       allow-patterns: ["git status*"]
 *       deny-patterns: ["curl *"]
 *     session:
 *       idle-timeout: 30m
 *       evict-interval: 5m
 *       approval-wait-timeout: 10m
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

        /**
         * 需要在调用前人工审批的工具名列表。
         * 为空时由工厂使用内置默认（shell / write_file / edit_file）。
         */
        private List<String> approvalTools;

        private Approval approval = new Approval();

        private Session session = new Session();
    }

    /** shell 静态评估名单，未配置的项沿用 {@code ApprovalPolicy} 内置默认 */
    @Data
    public static class Approval {
        /** 安全命令白名单（按命令名匹配），配置后整体替换内置名单 */
        private List<String> safeCommands;

        /** 危险命令黑名单（按命令名匹配），配置后整体替换内置名单 */
        private List<String> dangerousCommands;

        /** 越界检测正则，配置后整体替换内置规则 */
        private List<String> outsideCwdPatterns;

        /** 追加的 shell 通配符白名单，例如 "git status*"（整条子命令匹配） */
        private List<String> allowPatterns;

        /** 追加的 shell 通配符黑名单，优先级高于白名单 */
        private List<String> denyPatterns;
    }

    /** 会话生命周期治理 */
    @Data
    public static class Session {
        /** 空闲多久后回收 FREE 会话 */
        private Duration idleTimeout = Duration.ofMinutes(30);

        /** 回收任务执行间隔 */
        private Duration evictInterval = Duration.ofMinutes(5);

        /** 等待人工审批的最长时间，超时后放弃本轮审批并把会话置回空闲 */
        private Duration approvalWaitTimeout = Duration.ofMinutes(10);
    }
}
