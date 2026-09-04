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
 *   api-token:            # 为空（默认）不启用 API 鉴权，仅限本地开发模式
 *   checkpoint:
 *     redis:
 *       address: redis://127.0.0.1:6379
 *   agent:
 *     max-steps: 20
 *     system-prompt: ...
 *     global-context-file: ~/.agent/context.md
 *     project-context-file: .agent/context.md
 *     memory-dir: ~/.agent/memory
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

    /**
     * REST / WebSocket 握手的访问令牌。
     * 为空（默认，env: AGENTCODE_API_TOKEN 不设置）时不启用鉴权，
     * 仅为向后兼容本地开发模式；生产环境必须配置。
     */
    private String apiToken = "";

    private Checkpoint checkpoint = new Checkpoint();

    @Data
    public static class Audit {
        /** 是否包装 ChatModel 输出 AI 调用审计日志 */
        private boolean enabled = true;
    }

    /** checkpoint 存储配置 */
    @Data
    public static class Checkpoint {

        private Redis redis = new Redis();

        @Data
        public static class Redis {
            /** Redis 连接地址 */
            private String address = "redis://127.0.0.1:6379";

            /** 为空表示无密码 */
            private String password = "";

            /** Redis 逻辑库 */
            private int database = 0;
        }
    }

    @Data
    public static class Agent {
        /** 单次 run 允许的最大模型调用次数 */
        private int maxSteps = 12;

        /** 基础系统提示词，为空时只拼接上下文 */
        private String systemPrompt;

        /** 全局上下文文件（支持 ~ 前缀），不存在时忽略 */
        private String globalContextFile = "~/.agent/context.md";

        /** 项目上下文文件（相对 workspace），不存在时回退到 AGENT.md/CLAUDE.md/SOUL.md */
        private String projectContextFile = ".agent/context.md";

        /**
         * 长期记忆全局层根目录（支持 ~ 前缀）。每条记忆一个 markdown 文件 + MEMORY.md 索引；
         * 项目层记忆固定落在 {@code <workspace>/.agent/memory/}，不受本配置影响。
         */
        private String memoryDir = "~/.agent/memory";

        /**
         * 需要在调用前人工审批的工具名列表。
         * 为空时由工厂使用内置默认（shell / write_file / edit_file）。
         */
        private List<String> approvalTools;

        private Approval approval = new Approval();

        private Session session = new Session();

        // 抵达最大 Token 时压缩上下文
        private int maxTokensBeforeSummary = 4000;

        // 在压缩后保持多少条消息, 即之后的全部压缩
        private int messagesToKeep = 20;
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
