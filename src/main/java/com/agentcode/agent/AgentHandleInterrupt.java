package com.agentcode.agent;

public class AgentHandleInterrupt {
    public enum Decision{
        APPROVED,
        REJECTED,
        EDITED
    }


    private String runId; // 需要处理的会话ID

    private String toolCallId;

    private String toolName;

    private String arguments;

    private Decision decision; // 建议

    String feedback; // 当 decision 为 EDITED 时必须填写, 可以为空


}
