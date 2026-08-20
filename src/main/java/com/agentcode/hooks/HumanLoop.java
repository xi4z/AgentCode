package com.agentcode.hooks;

import com.alibaba.cloud.ai.graph.agent.hook.hip.HumanInTheLoopHook;

public class HumanLoop {
    private HumanLoop() {
        HumanInTheLoopHook.builder()
                .approvalOn(java.util.Map.of())
                .build();
    }
}
