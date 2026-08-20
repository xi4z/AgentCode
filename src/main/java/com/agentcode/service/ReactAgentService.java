package com.agentcode.service;

import com.agentcode.agent.AgentInterruptHandle;
import com.agentcode.agent.AgentStream;
import reactor.core.publisher.Flux;

/**
 * ReactAgent 核心类
 * TODO 向
 */
public interface ReactAgentService {
    /**
     * 进行执行指令
     * @param goal
     * @return
     */
    Flux<AgentStream> startNewSession(String goal, String workspace); // 新建 AgentContext
    Flux<AgentStream> run(String goal, String runId); // 跑已有 AgentContext


    void stop(String runId); // 直接中断会话

    /**
     * 处理 Agent 因为权限审批而造成的中断
     * @param handle
     */
    void handleInterrupt(AgentInterruptHandle handle);

    /**
     * 对正在进行中的 Agent 行动做出引导, 此时 Status 必须为 Running
     * 与上方的 handleInterrupt 不同, 前者是 Agent 发起的, 后者是用户发起的, 且用户发起的在本项目设计中只会打断思考而不是打断会话
     * @param runId
     * @param guidanceMessage
     */
    void interrupt(String runId, String guidanceMessage);
}
