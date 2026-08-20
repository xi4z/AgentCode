package com.agentcode.agent;

import com.alibaba.cloud.ai.graph.action.InterruptionMetadata;
import lombok.AllArgsConstructor;
import lombok.Getter;

@AllArgsConstructor
@Getter
// 对由 Agent 引发中断的处理
public class AgentInterruptHandle {
    public enum Decision{
        APPROVED,
        APPROVE_ALL,
        REJECTED,
        EDITED
    }

    private String runId; // 需要处理的会话ID

    private String id;
    private String name;
    private String arguments;
    private String description;

    private Decision decision; // 决定

    String feedback; // 当 decision 为 EDITED 时必须填写, 可以为空


}
