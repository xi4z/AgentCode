package com.agentcode.context;

public record ToolResultBlock(String toolUseId, String content, boolean isError) implements ContextBlock {
}
