package com.agentcode.service;

import com.agentcode.dto.AgentInterruptHandle;
import com.agentcode.dto.AgentStream;
import reactor.core.publisher.Flux;

/**
 * ReactAgent 会话服务：负责创建/运行会话、停止与引导，以及权限审批的恢复。
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
     * 处理 Agent 因为权限审批而造成的中断。
     *
     * <p>一轮 interruption 可能挂起多个工具，调用方可以逐个提交（服务端会等本轮答复齐后
     * 再恢复），也可以一次性提交全部决定。返回的事件流在仍缺少答复时只包含一个
     * {@code PERMISSION_PENDING} 事件，真正恢复后才包含后续 Agent 事件。
     *
     * @param handles 同一会话内的一个或多个审批决定
     * @return 恢复后的 Agent 事件流
     */
    Flux<AgentStream> handleInterrupt(String runId, AgentInterruptHandle... handles);

    /**
     * 对正在进行中的 Agent 行动做出引导, 此时 Status 必须为 Running
     * 与上方的 handleInterrupt 不同, 前者是 Agent 发起的, 后者是用户发起的, 且用户发起的在本项目设计中只会打断思考而不是打断会话
     * @param runId
     * @param guidanceMessage
     */
    void interrupt(String runId, String guidanceMessage);
}
