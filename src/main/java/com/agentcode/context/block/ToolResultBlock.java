package com.agentcode.context.block;

public record ToolResultBlock(String toolUseId, String content, boolean isError) implements ContextBlock {
}
