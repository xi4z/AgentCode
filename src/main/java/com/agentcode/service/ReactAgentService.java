package com.agentcode.service;

import com.agentcode.agent.AgentStream;
import reactor.core.publisher.Flux;

public interface ReactAgentService {
    /**
     * 进行执行指令
     * @param goal
     * @param runId
     * @return
     */
    Flux<AgentStream> run(String goal, String runId);

    /**
     * 对正在执行的会话进行强制关闭
     * 返回值还没想好
     * @param runId
     */
    void stop(String runId);

}
