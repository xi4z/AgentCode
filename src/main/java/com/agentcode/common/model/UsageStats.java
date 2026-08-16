package com.agentcode.common.model;

public record UsageStats(
        long inputTokens, // 输入 Token
        long outputTokens, // 输出 Token
        long cacheReadInputTokens, // 缓存的读Token
        long cacheCreationInputTokens,
        double contextPct) {
}
