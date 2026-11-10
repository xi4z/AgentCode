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
 *
 * <p>工作区之间的 Agent 不共享工具与会话缓存；状态存储由 Registry 统一持有。
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

        // 只注册自定义 shell；文件、搜索等工具由 Harness 按工作区自动装配。
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
                        // project 与 workspace 指向同一目录：文件工具与 shell 的根都落在这里。
                        .filesystem(
                                new LocalFilesystemSpec()
                                        .project(workspace)
                                        .inheritEnv(true))
                        // 会话事件写入工作区的 .agentscope-runtime，随工作区一起备份。
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
            // 默认关闭长期记忆：它会在后台调用模型做整理，离线测试与配额控制都不需要。
            builder.disableMemoryTools().disableMemoryHooks();
        }

        this.agent = builder.build();
    }

    /** 返回当前运行时绑定的工作区。 */
    public Path workspace() {
        return workspace;
    }

    /** 取得指定用户与会话的 AgentSession；同一身份会复用同一会话状态。 */
    public AgentSession session(String userId, String sessionId) {
        RuntimeContext context =
                RuntimeContext.builder().userId(userId).sessionId(sessionId).build();
        return agent.session(context);
    }

    /** 暴露底层 HarnessAgent，供审批规则写回等集成操作使用。 */
    public HarnessAgent agent() {
        return agent;
    }

    @Override
    public void close() {
        agent.close();
    }
}
