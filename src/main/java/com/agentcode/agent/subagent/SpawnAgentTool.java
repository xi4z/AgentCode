package com.agentcode.agent.subagent;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

@Component
public class SpawnAgentTool {

    @Tool(description = "Spawn an isolated sub-agent")
    public String spawn(String prompt) {
        // TODO: 创建子 Agent 并执行
        return "";
    }
}
