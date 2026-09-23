package com.agentcode.session;

/**
 * 会话对外可见的运行态。
 *
 * 由 {@link AgentSession} 从内部唯一的状态位推导，不再自己维护可变状态、转换表和锁：
 * - RUNNING：有 run 正持有运行槽
 * - INTERRUPTED：没有 run，但 Agent 停在审批中断点上等待用户反馈
 * - FREE：其他情况
 */
public enum SessionStatus {
    /**
     * 会话空闲中
     */
    FREE,

    /**
     * 会话正在运行
     */
    RUNNING,

    /**
     * 会话中断, 等待恢复
     */
    INTERRUPTED
}
