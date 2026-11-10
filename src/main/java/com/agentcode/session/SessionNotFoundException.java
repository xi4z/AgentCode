package com.agentcode.session;

public class SessionNotFoundException extends RuntimeException {

    public SessionNotFoundException(String runId) {
        super("会话不存在: " + runId);
    }
}
