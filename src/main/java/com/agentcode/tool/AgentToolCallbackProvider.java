package com.agentcode.tool;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;

public class AgentToolCallbackProvider implements ToolCallbackProvider {

    @Override
    public ToolCallback[] getToolCallbacks() {
        // TODO: 返回注册表中的全部 ToolCallback
        return new ToolCallback[0];
    }
}
