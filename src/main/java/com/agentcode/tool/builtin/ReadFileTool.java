package com.agentcode.tool.builtin;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class ReadFileTool {

    @Tool(description = "Read a text file, path relative to cwd")
    public String read(@ToolParam(description = "file path") String path) {
        // TODO: 读取文件内容
        return "";
    }
}
