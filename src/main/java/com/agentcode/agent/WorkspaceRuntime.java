package com.agentcode.agent;

import com.agentcode.properties.AgentCodeProperties;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.model.Model;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec;
import io.agentscope.harness.agent.session.AgentSession;
import io.agentscope.harness.agent.session.WorkspaceSessionLogStore;

import java.nio.file.Path;

/**
 * 一个工作区对应一个 HarnessAgent。会话由 AgentScope 的 AgentSession 承担，
 * 工作区、会话日志、状态存储都在创建时绑定。
 */
public final class WorkspaceRuntime implements AutoCloseable {

    private final Path workspace;
    private final HarnessAgent agent;

    WorkspaceRuntime(
            Path workspace,
            AgentCodeProperties properties,
            Model model,
            AgentStateStore stateStore,
            ShellPolicy shellPolicy,
            PermissionPolicy permissionPolicy) {
        this.workspace = workspace;
        AgentCodeProperties.Agent agentProperties = properties.getAgent();

        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new ShellTool(workspace, shellPolicy));

        HarnessAgent.Builder builder =
                HarnessAgent.builder()
                        .name(agentProperties.getName())
                        .agentId(agentProperties.getAgentId())
                        .description("AgentCode coding agent")
                        .model(model)
                        .sysPrompt(
                                WorkspacePrompt.build(
                                        agentProperties.getSystemPrompt(), workspace))
                        .workspace(workspace)
                        .filesystem(
                                new LocalFilesystemSpec()
                                        .project(workspace)
                                        .inheritEnv(true))
                        .sessionLogStore(
                                new WorkspaceSessionLogStore(new LocalFilesystem(workspace)))
                        .stateStore(stateStore)
                        .toolkit(toolkit)
                        .permissionContext(permissionPolicy.context())
                        .maxIters(agentProperties.getMaxIterations())
                        .enablePendingToolRecovery(true)
                        .disableShellTool()
                        .disableWebTools()
                        .disableToolsConfig()
                        .disableSubagents()
                        .disableDynamicSubagents()
                        .disableDynamicSkills()
                        .disableDefaultWorkspaceSkills()
                        .skillsEnabled(false)
                        .disableWorkspaceContext()
                        .disableAtPathExpansion();

        if (!agentProperties.isMemoryEnabled()) {
            builder.disableMemoryTools().disableMemoryHooks();
        }

        this.agent = builder.build();
    }

    public Path workspace() {
        return workspace;
    }

    public AgentSession session(String userId, String sessionId) {
        RuntimeContext context =
                RuntimeContext.builder().userId(userId).sessionId(sessionId).build();
        return agent.session(context);
    }

    public HarnessAgent agent() {
        return agent;
    }

    @Override
    public void close() {
        agent.close();
    }
}
