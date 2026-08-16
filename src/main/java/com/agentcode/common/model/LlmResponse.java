package com.agentcode.common.model;

import java.util.List;

/**
 * 模型恢复类
 * @param text 回复的文本
 * @param toolCalls 调用的工具
 * @param stopReason 完成回复的原因
 * @param usage 用量状态
 * @param thinkingBlocks 思考
 */
public record LlmResponse(
        String text,
        List<ToolCallBlock> toolCalls,
        StopReason stopReason,
        UsageStats usage,
        List<Object> thinkingBlocks) {
}
