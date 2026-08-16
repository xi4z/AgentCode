package com.agentcode.tool.builtin;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class WriteFileTool {

    @Tool(description = "Write text content to a file")
    public String write(
            @ToolParam(description = "file path") String path,
            @ToolParam(description = "file content") String content) {
        // TODO: 写入文件
        return "";
    }
}
