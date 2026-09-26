package com.agentcode.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agentcode")
@Data
public class AgentCodeProperties {


    String systemPrompt;
    int maxStep;

    /** 长期记忆（跨会话）：三层见 {@link Memory} */
    private Memory memory = new Memory();

    /** 上下文压缩：历史摘要开关与阈值 */
    private Summarization summarization = new Summarization();

    @Data
    public static class Memory {

        /** 总开关：关掉则不注册记忆工具、也不往 system prompt 注入记忆块 */
        private boolean enabled = true;

        /** 工作区里 agent 可自由读写的记忆文件（相对工作区路径） */
        private String file = ".memory/memory.md";

        /** 工作区里人写、agent 只读的约定文件 */
        private String workspaceFile = "Agent.md";

        /**
         * 服务核心配置持有的全局记忆：只读，agent 改不了（改这里要动配置并重启）。
         * 多行文本，支持 \\n 分隔。
         */
        private String global = "";
    }

    @Data
    public static class Summarization {

        /** 总开关：关掉则不挂 SummarizationHook，历史消息不压缩 */
        private boolean enabled = true;

        /** 超过这么多 token 触发摘要 */
        private int maxTokens = 4000;

        /** 摘要时保留最近多少条消息 */
        private int keepMessages = 20;
    }
}
