package com.agentcode.service;

import com.agentcode.agent.AgentStream;

public interface ReactAgentService {
    AgentStream run(String goal, String runId);
}
