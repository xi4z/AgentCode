package com.agentcode.context;

import com.agentcode.context.block.ContextBlock;
import lombok.Data;

import java.util.List;

@Data
public class AgentMessage {
    // TODO: role / content / toolUseId / isError
    public enum Role{
        USER,
        ASSISTANT
    }

    final Role role;
    final List<ContextBlock> contexts;
}
