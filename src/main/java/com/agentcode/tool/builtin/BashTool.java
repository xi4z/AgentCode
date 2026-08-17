package com.agentcode.tool.builtin;

import com.alibaba.cloud.ai.graph.agent.tools.ShellTool;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class BashTool {

    @Tool(description = "Execute a shell command")
    public String execute(@ToolParam(description = "shell command") String command) {
        // TODO: 执行 shell 命令并返回输出
        return "";
    }
}
