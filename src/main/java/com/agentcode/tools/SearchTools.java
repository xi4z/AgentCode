package com.agentcode.tools;

import com.alibaba.cloud.ai.graph.agent.tools.GlobSearchTool;
import com.alibaba.cloud.ai.graph.agent.tools.GrepSearchTool;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 搜索工具
 */
public class SearchTools {

    @Tool(description = "Fast content search tool that works with any codebase size. Searches file contents using regular expressions. Supports full regex syntax and filters files by pattern with the include parameter. Use this tool when you need to search for specific content within files.")
    String grepTool(@ToolParam String searchContent){
        // TODO 可能要做权限校验
        ToolCallback grepSearch = GrepSearchTool.builder("/workspace")
                .withName("grep_search")
                .withUseRipgrep(true)
                .withMaxFileSizeMb(10)
                .build();

        return grepSearch.call(searchContent);
    }

    @Tool(description = "Fast file pattern matching tool that works with any codebase size. Supports glob patterns like **/*.js or src/**/*.ts. Returns matching file paths sorted by modification time. Use this tool when you need to find files by name patterns.")
    String globTool(@ToolParam String searchContent){
        ToolCallback globSearch = GlobSearchTool.builder("/workspace")
                .withName("glob_search")
                .build();
        return globSearch.call(searchContent);
    }

}
