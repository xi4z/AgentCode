package com.agentcode.tool.builtin;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class ListDirTool {

    @Tool(description = "List directory contents as a tree")
    public String list(@ToolParam(description = "directory path") String path) {
        // TODO: 列出目录
        return "";
    }
}
