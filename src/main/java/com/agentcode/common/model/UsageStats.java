package com.agentcode.common.model;

public record UsageStats(
        long inputTokens,
        long outputTokens,
        long cacheReadInputTokens,
        long cacheCreationInputTokens,
        double contextPct) {
}
