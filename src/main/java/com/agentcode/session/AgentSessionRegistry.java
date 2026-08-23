package com.agentcode.session;

import com.agentcode.agent.AgentSession;
import com.agentcode.context.AgentContext;

/**
 * 会话注册表：负责管理 AgentSession 的创建、获取、移除与清理。
 *
 * 当前提供内存实现，后续可替换为 Redis / 数据库等持久化实现。
 */
public interface AgentSessionRegistry {

    /**
     * 获取指定会话；不存在时根据 AgentContext 创建并注册。
     */
    AgentSession getOrCreate(String runId, AgentContext context);

    /**
     * 获取指定会话，不存在时抛 SessionNotFoundException。
     */
    AgentSession get(String runId);

    /**
     * 移除指定会话。
     */
    void remove(String runId);

    /**
     * 是否存在指定会话。
     */
    boolean contains(String runId);

    /**
     * 清理空闲超时且状态为 FREE 的会话。
     */
    void cleanup();

    /**
     * 当前注册的会话数量。
     */
    int size();
}
