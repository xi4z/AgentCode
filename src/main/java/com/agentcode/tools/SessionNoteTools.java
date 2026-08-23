package com.agentcode.tools;

import com.agentcode.context.AgentContext;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import lombok.Builder;
import lombok.NoArgsConstructor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

@NoArgsConstructor
public class SessionNoteTools {
    @Tool(description = "用于重写当前会话的记忆, 比如有较大的会话改动时使用, 请谨慎使用")
    public void updateNote(String content, ToolContext toolContext) {
        AgentContext context = (AgentContext) toolContext.getContext().get("__AGENT_CONTEXT__");
        context.getSessionNotes().replace(
                0,
                context.getSessionNotes().length(),
                content
        );
    }

    @Tool(description = "用于追加当前会话的记忆, 比如有临时性的消息可以使用")
    public void appendNote(@ToolParam(description = "你需要追加的内容") String content, ToolContext toolContext) {
        AgentContext context = (AgentContext) toolContext.getContext().get("__AGENT_CONTEXT__");
        context.getSessionNotes().append(content);
    }
}
