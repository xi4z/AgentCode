package com.agentcode.agent.subagent;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

@Component
public class AgentResultTool {

    @Tool(description = "Retrieve background sub-agent result")
    public String result(String runId) {
        // TODO: 查询后台子 Agent 结果
        return "";
    }
}
