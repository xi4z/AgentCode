package com.agentcode.context;

import io.a2a.spec.Message;

import java.util.List;

public class AgentMessage {
    // TODO: role / content / toolUseId / isError
    public enum role{
        USER,
        ASSISTANT
    }

    final Message.Role role;
    final List<ContextBlock> contexts;
}
