package com.agentcode.factory;

import com.agentcode.agent.AgentContext;
import com.agentcode.dto.AgentApprovalManager;
import com.agentcode.dto.ApprovalPolicy;
import com.agentcode.properties.AgentCodeProperties;
import com.agentcode.agent.AgentSession;
import com.agentcode.agent.AgentSessionRuntime;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.agentcode.common.SessionConfigKeys;
import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static com.agentcode.agent.AgentContext.DEFAULT_PROJECT_CONTEXT_FILES;

/**
 * AgentSession 工厂：负责组装 ReactAgent、Hooks、Tools、RunnableConfig。
 */
@Component
@RequiredArgsConstructor
public class AgentSessionFactory {

    private final ChatModel chatModel;
    private final BaseCheckpointSaver saver;
    private final AgentCodeProperties agentCodeProperties;
    private final AgentHookBuilder agentHookBuilder;
    private final AgentToolBuilder agentToolBuilder;
    private final AgentInterceptorBuilder agentInterceptorBuilder;


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

        // 获取需要审批的工具名单并组装审批管理器
        List<String> approvalTools = options.getApprovalTools() == null
                ? List.of()
                : List.copyOf(options.getApprovalTools());
        AgentApprovalManager approvalManager = new AgentApprovalManager(
                agentContext, ApprovalPolicy.from(agentConfig.getApproval()));

        // 拼接提示词
        String systemPrompt = options.getSystemPrompt();
        AgentHookBuilder.Result result = agentHookBuilder.builder(agentContext)
                .withModelCallLimit()
                .withSummarization()
                .withShellTool()
                .withApproval(approvalTools)
                .withSkills()
                .build();


        ReactAgent reactAgent = ReactAgent.builder()
                .name("minimal_agent")
                .model(chatModel)
                .systemPrompt(agentContext.systemPrompt(systemPrompt))
                .saver(saver)
                .toolContext(Map.of(SessionConfigKeys.AGENT_CONTEXT, agentContext))
                .tools(agentToolBuilder.builder(agentContext).mainAgent().withSubAgent(this.createSubAgent(agentContext)).build())
                .hooks(result.getHooks())
                .interceptors(agentInterceptorBuilder.builder(agentContext).withTodoList().build())
                .build();

        // 在重新 run 之后, 修改 context 状态
        RunnableConfig config = RunnableConfig.builder()
                .threadId(agentContext.getRunId()) // 获取数据
                .build();
        config.context().put(SessionConfigKeys.AGENT_CONTEXT, agentContext);


        AgentSessionRuntime runtime = AgentSessionRuntime.builder()
                .reactAgent(reactAgent)
                .shellTool2(result.getShellTool2())
                .approvalManager(approvalManager)
                .initialConfig(config)
                .build();

        return new AgentSession(agentContext, runtime);
    }

    private ReactAgent createSubAgent(AgentContext agentContext) {
        return ReactAgent.builder()
                .name("sub_agent")
                .model(chatModel)
                .saver(saver)
                .description("Run an isolated sub-agent to handle a delegated sub-task.")
                .tools(agentToolBuilder.builder(agentContext).subAgent().build())
                .hooks(agentHookBuilder.builder(agentContext)
                        .withModelCallLimit()
                        .withSummarization()
                        .withSkills().build().getHooks())
                .build();
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


}
