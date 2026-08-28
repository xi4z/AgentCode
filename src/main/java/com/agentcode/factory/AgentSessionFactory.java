package com.agentcode.factory;

import com.agentcode.context.AgentContext;
import com.agentcode.dto.AgentApprovalManager;
import com.agentcode.dto.ApprovalPolicy;
import com.agentcode.properties.AgentCodeProperties;
import com.agentcode.session.AgentSession;
import com.agentcode.session.AgentSessionRuntime;
import com.agentcode.tools.SessionNoteTools;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.Hook;
import com.alibaba.cloud.ai.graph.agent.hook.hip.HumanInTheLoopHook;
import com.alibaba.cloud.ai.graph.agent.hook.hip.ToolConfig;
import com.alibaba.cloud.ai.graph.agent.hook.modelcalllimit.ModelCallLimitHook;
import com.alibaba.cloud.ai.graph.agent.hook.shelltool.ShellToolAgentHook;
import com.agentcode.common.SessionConfigKeys;
import com.alibaba.cloud.ai.graph.agent.hook.skills.SkillsAgentHook;
import com.alibaba.cloud.ai.graph.agent.hook.summarization.SummarizationHook;
import com.alibaba.cloud.ai.graph.agent.extension.tools.filesystem.FileSystemTools;
import com.alibaba.cloud.ai.graph.agent.tools.GlobSearchTool;
import com.alibaba.cloud.ai.graph.agent.tools.GrepSearchTool;
import com.alibaba.cloud.ai.graph.agent.tools.ShellTool2;
import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.alibaba.cloud.ai.graph.skills.registry.filesystem.FileSystemSkillRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static com.agentcode.context.AgentContext.DEFAULT_PROJECT_CONTEXT_FILES;

/**
 * AgentSession 工厂：负责组装 ReactAgent、Hooks、Tools、RunnableConfig。
 */
@Component
@RequiredArgsConstructor
public class AgentSessionFactory {

    private final ChatModel chatModel;
    private final BaseCheckpointSaver saver;
    private final AgentCodeProperties agentCodeProperties;

    public AgentSession create(AgentContext agentContext) {
        AgentCodeProperties.Agent agentConfig = agentConfig();
        return create(agentContext, SessionBuildOptions.builder()
                .systemPrompt(agentConfig.getSystemPrompt())
                .approvalTools(resolveApprovalTools(agentConfig))
                .build());
    }

    public AgentSession create(AgentContext agentContext, SessionBuildOptions options) {
        AgentCodeProperties.Agent agentConfig = agentConfig();
        // 配置文件路径与模型步数来自 agentcode.agent.*，必须在拼接 system prompt 之前注入
        applyContextConfig(agentContext, agentConfig);

        List<String> approvalTools = options.getApprovalTools() == null
                ? List.of()
                : List.copyOf(options.getApprovalTools());
        String systemPrompt = options.getSystemPrompt();
        AgentApprovalManager approvalManager = new AgentApprovalManager(
                agentContext, ApprovalPolicy.from(agentConfig.getApproval()));
        String workspace = resolveWorkspace(agentContext);
        ShellTool2 shellTool2 = ShellTool2.builder(workspace).build();

        List<Hook> hooks = new ArrayList<>(
                List.of(
                        ShellToolAgentHook.builder().shellTool2(shellTool2).shellToolName("shell").build(), // shell Hooks, 在审批前后防止 Shell 会话中断
                        SummarizationHook.builder().model(chatModel).maxTokensBeforeSummary(4000).messagesToKeep(20).build(), // Token 成本控制
                        ModelCallLimitHook.builder().runLimit(agentConfig.getMaxSteps()).build(), // 调用控制，取 agentcode.agent.max-steps
                        SkillsAgentHook.builder().skillRegistry(FileSystemSkillRegistry.builder()
                                .projectSkillsDirectory(workspace + "/skills")
                                .build()).build() // Skill 侧控制
                )
        );

        if (!approvalTools.isEmpty()) {
            HumanInTheLoopHook.Builder hitlBuilder = HumanInTheLoopHook.builder();
            // 需要人工审批的工具通过 HumanInTheLoopHook 在调用前中断
            for (String tool : approvalTools) {
                hitlBuilder.approvalOn(tool, ToolConfig.builder()
                        .description("该工具调用需要人工审批")
                        .build());
            }
            hooks.add(hitlBuilder.build());
        }

        ReactAgent reactAgent = ReactAgent.builder()
                .name("minimal_agent")
                .model(chatModel)
                .systemPrompt(agentContext.systemPrompt(systemPrompt))
                .saver(saver)
                .tools(List.of(
                        GrepSearchTool.builder(workspace).build(),
                        GlobSearchTool.builder(workspace).build())
                )
                .methodTools(
                        FileSystemTools.builder().rootDir(workspace).maxFileSizeMb(10).build(),
                        new SessionNoteTools()
                )
                .toolContext(Map.of(SessionConfigKeys.AGENT_CONTEXT, agentContext))
                .hooks(hooks)
                .build();

        // 在重新 run 之后, 修改 context 状态
        RunnableConfig config = RunnableConfig.builder()
                .threadId(agentContext.getRunId()) // 获取数据
                .build();
        config.context().put(SessionConfigKeys.AGENT_CONTEXT, agentContext);

        AgentSessionRuntime runtime = AgentSessionRuntime.builder()
                .reactAgent(reactAgent)
                .shellTool2(shellTool2)
                .approvalManager(approvalManager)
                .initialConfig(config)
                .build();

        return new AgentSession(agentContext, runtime);
    }

    /**
     * 需要人工审批的工具列表：优先 {@code agentcode.agent.approval-tools}，
     * 未配置时回落到 {@link SessionBuildOptions#DEFAULT_APPROVAL_TOOLS}。
     */
    private List<String> resolveApprovalTools(AgentCodeProperties.Agent agent) {
        List<String> configured = agent.getApprovalTools();
        return configured == null || configured.isEmpty()
                ? SessionBuildOptions.DEFAULT_APPROVAL_TOOLS
                : List.copyOf(configured);
    }

    /**
     * 属性未注册（如单测直接 new 工厂）时也要拿到一份可用默认值，避免 NPE 与配置漂移。
     */
    private AgentCodeProperties.Agent agentConfig() {
        AgentCodeProperties.Agent agent = agentCodeProperties == null ? null : agentCodeProperties.getAgent();
        return agent != null ? agent : new AgentCodeProperties.Agent();
    }

    /**
     * 把 agentcode.agent.* 中的上下文文件配置注入会话上下文：
     * 配置的项目上下文文件排在默认候选之前，并去重。
     */
    private void applyContextConfig(AgentContext agentContext, AgentCodeProperties.Agent agent) {
        if (agentContext == null) {
            return;
        }
        if (agent.getGlobalContextFile() != null && !agent.getGlobalContextFile().isBlank()) {
            agentContext.setGlobalContextFile(agent.getGlobalContextFile());
        }
        if (agent.getProjectContextFile() != null && !agent.getProjectContextFile().isBlank()) {
            LinkedHashSet<String> candidates = new LinkedHashSet<>();
            candidates.add(agent.getProjectContextFile());
            candidates.addAll(DEFAULT_PROJECT_CONTEXT_FILES);
            agentContext.setProjectContextFiles(new ArrayList<>(candidates));
        }
    }

    private String resolveWorkspace(AgentContext agentContext) {
        String workspace = agentContext.getWorkspace();
        if (workspace == null || workspace.isBlank()) {
            return System.getProperty("user.dir");
        }
        return workspace;
    }
}
