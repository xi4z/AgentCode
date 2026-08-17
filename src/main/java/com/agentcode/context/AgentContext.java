package com.agentcode.context;

import com.agentcode.common.model.ContextStatus;
import com.agentcode.context.block.TextBlock;
import com.agentcode.context.block.ToolResultBlock;

import java.util.ArrayList;
import java.util.List;

public class AgentContext {
    // TODO: runId / goal / step / status / result / messages / maxSteps
    String runId;
    String goal;

    int step;
    int maxSteps;

    ContextStatus status;
    String result;


    // 上下文
    List<AgentMessage> messages; // 会话记忆
    String systemPromptOverride;

    // 会话上下文, 包括对全局记忆, 项目记忆, 还有在会话中遇见的短期记忆
    // 在每轮开始时都应更新一次
    String globalContext;
    String projectContext;
    String sessionNotes;

    public boolean addAssistantMessage(List<AgentMessage> blocks) {
        this.messages.addAll(blocks);
        return true;
    }

    /**
     * 插入 ToolResult
     * @param toolUseId
     * @param content
     * @param isError
     * @return
     */
    public boolean addToolResult(String toolUseId, String content, boolean isError){
        AgentMessage lastMessage = messages.getLast();
        // 准备插入的数据
        ToolResultBlock block = new ToolResultBlock(toolUseId, content, isError);
        if (!lastMessage.getRole().equals(AgentMessage.Role.USER)) {
            lastMessage = new AgentMessage(
                    AgentMessage.Role.USER,
                    new ArrayList<>()
            );
            this.messages.add(lastMessage);
        }

        // 进行 block 插入
        lastMessage.getContexts().add(block);
        return true;
    }

    public boolean addText(String Text){
        return messages.getLast().getContexts().add(new TextBlock(Text));
    }

    // 拼接 system prompt
    public String systemPrompt(String base) {
        StringBuilder sb = new StringBuilder(base);
        if (globalContext != null && !globalContext.isBlank()) {
            sb.append("\n\n## Global Context\n").append(globalContext);
        }
        if (projectContext != null && !projectContext.isBlank()) {
            sb.append("\n\n## Project Context\n").append(projectContext);
        }
        if (sessionNotes != null && !sessionNotes.isBlank()) {
            sb.append("\n\n## Session Notes\n").append(sessionNotes);
        }
        return sb.toString();
    }

}
