package com.agentcode.event;

import org.springframework.stereotype.Component;

@Component
public class AgentEventPublisher {

    public void publish(AgentEvent event) {
        // TODO: 发布到 Spring ApplicationEventPublisher / WebSocket / SSE
    }
}
