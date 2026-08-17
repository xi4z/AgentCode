package com.agentcode.service;

import com.agentcode.agent.AgentResult;

public interface ReactAgentService {
    AgentResult run(String goal, String runId);
}
