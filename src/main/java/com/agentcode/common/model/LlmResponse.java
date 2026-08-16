package com.agentcode.common.model;

import java.util.List;

public record LlmResponse(
        String text,
        List<ToolCallBlock> toolCalls,
        StopReason stopReason,
        UsageStats usage,
        List<Object> thinkingBlocks) {
}
