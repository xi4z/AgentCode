package com.agentcode.agent;

import com.agentcode.dto.AgentApprovalManager;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.tools.ShellTool2;
import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import lombok.Builder;
import lombok.Getter;

/**
 * AgentSession 的运行依赖聚合。
 *
 * 由 AgentSessionFactory 组装，AgentSession 只依赖该运行时对象，
 * 不直接感知 ChatModel、BaseCheckpointSaver、Hook 等装配细节。
 */
@Getter
@Builder
public class AgentSessionRuntime {

    private final ReactAgent reactAgent;
    private final ShellTool2 shellTool2;
    private final AgentApprovalManager approvalManager;
    private final RunnableConfig initialConfig;
    private final BaseCheckpointSaver saver;
}
