package com.agentcode.context;

import com.agentcode.common.model.ContextStatus;

import java.util.List;

public class AgentContext {
    // TODO: runId / goal / step / status / result / messages / maxSteps
    String runId;
    String goal;
    int step;
    ContextStatus status;
    String result;
    List<AgentMessage> messages;
    int maxSteps;

    public boolean addAssistantMessage(List<AgentMessage> blocks) {
        this.messages.addAll(blocks);
        return true;
    }

    public boolean addToolResult(String toolUseId, String content, boolean isError){
        AgentMessage lastMessage = messages.getLast();

    }



}
