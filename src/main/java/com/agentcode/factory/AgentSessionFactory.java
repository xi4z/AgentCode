package com.agentcode.factory;

import com.agentcode.context.AgentContext;
import com.agentcode.session.AgentSession;
import com.alibaba.cloud.ai.graph.agent.hook.Hook;
import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Hooks;

import java.util.List;

public class AgentSessionFactory {
    // TODO AgentSession 构建类

    AgentSession create(AgentContext agentContext, List<Hook> hooks) {
        return null;
    }
    AgentSession create(AgentContext agentContext) {
        return null;
    }






}
