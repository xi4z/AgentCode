package com.agentcode.service;

import com.agentcode.agent.AgentStream;
import reactor.core.publisher.Flux;

public interface ReactAgentService {
    Flux<AgentStream> run(String goal, String runId);
}
