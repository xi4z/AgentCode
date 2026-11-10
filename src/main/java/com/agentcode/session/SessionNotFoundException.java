package com.agentcode.session;

/** 客户端引用的 runId 没有对应元数据时抛出。 */
public class SessionNotFoundException extends RuntimeException {

    public SessionNotFoundException(String runId) {
        super("会话不存在: " + runId);
    }
}
