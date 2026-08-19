package com.agentcode.exception;

public class AgentAlreadyRunningException extends RuntimeException {
    public AgentAlreadyRunningException(String message) {
        super(message);
    }
}
