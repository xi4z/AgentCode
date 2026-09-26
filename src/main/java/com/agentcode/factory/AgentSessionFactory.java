package com.agentcode.factory;

import com.agentcode.agent.manager.HooksManager;
import com.agentcode.agent.manager.InterceptorManager;
import com.agentcode.agent.manager.ToolManager;
import com.agentcode.agent.context.AgentContext;
import com.agentcode.dto.AgentApprovalManager;
import com.agentcode.memory.FileMemoryStore;
import com.agentcode.memory.MemoryStore;
import com.agentcode.properties.AgentCodeProperties;
import com.agentcode.session.AgentSession;
import com.agentcode.session.AgentSessionRuntime;
import com.agentcode.session.SessionEnum;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.Hook;
import com.alibaba.cloud.ai.graph.agent.interceptor.Interceptor;
import com.alibaba.cloud.ai.graph.agent.tools.ShellTool2;
import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * AgentSession 工厂：负责组装 ReactAgent、Hooks、Tools、Interceptors、RunnableConfig。
 *
 * 工具 / Hook / 拦截器分别交由 ToolManager、HooksManager、InterceptorManager 装配，
 * 工厂只负责把它们交给 ReactAgent。
 */
@Component
@RequiredArgsConstructor
public class AgentSessionFactory {

    private final ChatModel chatModel;
    private final BaseCheckpointSaver saver;
    private final AgentCodeProperties agentCodeProperties;

    /** 记忆库每个工厂一份：写入是 read-modify-write，共用一把锁才不会互相覆盖 */
    private volatile MemoryStore memoryStore;

    public AgentSession create(AgentContext agentContext) {
        String systemPrompt = agentCodeProperties == null ? null : agentCodeProperties.getSystemPrompt();
        return create(agentContext, SessionBuildOptions.builder()
                .systemPrompt(systemPrompt)
                .build());
    }

    public AgentSession create(AgentContext agentContext, SessionBuildOptions options) {
        List<String> approvalTools = options.getApprovalTools() == null
                ? List.of()
                : List.copyOf(options.getApprovalTools());
        String systemPrompt = options.getSystemPrompt();
        AgentApprovalManager approvalManager = new AgentApprovalManager(agentContext);
        String workspace = resolveWorkspace(agentContext);
        ShellTool2 shellTool2 = ShellTool2.builder(workspace).build();

        boolean memoryEnabled = options.getMemoryEnabled() != null
                ? options.getMemoryEnabled()
                : agentCodeProperties != null && agentCodeProperties.getMemory() != null
                && agentCodeProperties.getMemory().isEnabled();
        MemoryStore memory = memoryEnabled ? memoryStore() : null;

        boolean summarizationEnabled = options.getSummarizationEnabled() != null
                ? options.getSummarizationEnabled()
                : agentCodeProperties == null || agentCodeProperties.getSummarization() == null
                || agentCodeProperties.getSummarization().isEnabled();
        int summarizationMaxTokens = options.getSummarizationMaxTokens() != null
                ? options.getSummarizationMaxTokens()
                : agentCodeProperties == null || agentCodeProperties.getSummarization() == null
                ? 4000 : agentCodeProperties.getSummarization().getMaxTokens();
        int summarizationKeepMessages = options.getSummarizationKeepMessages() != null
                ? options.getSummarizationKeepMessages()
                : agentCodeProperties == null || agentCodeProperties.getSummarization() == null
                ? 20 : agentCodeProperties.getSummarization().getKeepMessages();

        HooksManager.Builder hookBuilder = HooksManager.builder(chatModel, workspace)
                .performance() // 模型调用与轮次审计日志
                .shell(shellTool2); // shell Hooks, 在审批前后防止 Shell 会话中断
        if (summarizationEnabled) {
            hookBuilder.summarization(summarizationMaxTokens, summarizationKeepMessages); // Token 成本控制
        }
        List<Hook> hooks = hookBuilder
                .callLimit() // 调用控制
                .skill() // Skill 侧控制
                .approval(approvalTools) // 需要人工审批的工具在调用前中断
                .build();

        List<ToolCallback> tools = ToolManager.builder(workspace, memory).mainAgent().build();
        List<Interceptor> interceptors = InterceptorManager.builder().toolPerformance().build();

        String prompt = agentContext.systemPrompt(systemPrompt);
        if (memory != null) {
            String memoryBlock = memory.buildPromptBlock(workspace);
            if (!memoryBlock.isBlank()) {
                prompt = prompt + "\n\n" + memoryBlock;
            }
        }

        ReactAgent reactAgent = ReactAgent.builder()
                .name("minimal_agent")
                .model(chatModel)
                .systemPrompt(prompt)
                .saver(saver)
                .tools(tools)
                .interceptors(interceptors)
                .toolContext(Map.of(SessionEnum.AGENT_CONTEXT.getCode(), agentContext))
                .hooks(hooks)
                .build();

        // 在重新 run 之后, 修改 context 状态
        RunnableConfig config = RunnableConfig.builder()
                .threadId(agentContext.getRunId()) // 获取数据
                // metadata 是模型拦截器能从 ModelRequest.getContext() 看到的唯一东西，runId 从这里取
                .addMetadata(SessionEnum.AGENT_CONTEXT.getCode(), agentContext)
                .build();
        config.context().put(SessionEnum.AGENT_CONTEXT.getCode(), agentContext);

        AgentSessionRuntime runtime = AgentSessionRuntime.builder()
                .reactAgent(reactAgent)
                .shellTool2(shellTool2)
                .approvalManager(approvalManager)
                .initialConfig(config)
                .build();

        return new AgentSession(agentContext, runtime);
    }

    private MemoryStore memoryStore() {
        MemoryStore local = memoryStore;
        if (local == null) {
            synchronized (this) {
                if (memoryStore == null) {
                    AgentCodeProperties.Memory config = agentCodeProperties == null
                            || agentCodeProperties.getMemory() == null
                            ? new AgentCodeProperties.Memory()
                            : agentCodeProperties.getMemory();
                    memoryStore = new FileMemoryStore(config.getWorkspaceFile(), config.getFile(), config.getGlobal());
                }
                local = memoryStore;
            }
        }
        return local;
    }

    private String resolveWorkspace(AgentContext agentContext) {
        String workspace = agentContext.getWorkspace();
        if (workspace == null || workspace.isBlank()) {
            return System.getProperty("user.dir");
        }
        return workspace;
    }
}
