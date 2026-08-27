package com.agentcode.factory;

import com.agentcode.context.AgentContext;
import com.agentcode.dto.AgentApprovalManager;
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
import java.util.List;
import java.util.Map;

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

        List<Hook> hooks = new ArrayList<>(
                List.of(
                        ShellToolAgentHook.builder().shellTool2(shellTool2).shellToolName("shell").build(), // shell Hooks, 在审批前后防止 Shell 会话中断
                        SummarizationHook.builder().model(chatModel).maxTokensBeforeSummary(4000).messagesToKeep(20).build(), // Token 成本控制
                        ModelCallLimitHook.builder().runLimit(10).build(), // 调用控制
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
                .toolContext(Map.of("__AGENT_CONTEXT__", agentContext))
                .hooks(hooks)
                .build();

        // 在重新 run 之后, 修改 context 状态
        RunnableConfig config = RunnableConfig.builder()
                .threadId(agentContext.getRunId()) // 获取数据
                .build();
        config.context().put("__AGENT_CONTEXT__", agentContext);

        AgentSessionRuntime runtime = AgentSessionRuntime.builder()
                .reactAgent(reactAgent)
                .shellTool2(shellTool2)
                .approvalManager(approvalManager)
                .initialConfig(config)
                .build();

        return new AgentSession(agentContext, runtime);
    }

    private String resolveWorkspace(AgentContext agentContext) {
        String workspace = agentContext.getWorkspace();
        if (workspace == null || workspace.isBlank()) {
            return System.getProperty("user.dir");
        }
        return workspace;
    }
}
