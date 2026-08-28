package com.agentcode.tools;

import com.agentcode.common.SessionConfigKeys;
import com.agentcode.context.AgentContext;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 会话笔记工具。
 *
 * AgentContext 通过 ReactAgent builder 的 toolContext 注入，
 * 键名见 {@link SessionConfigKeys#AGENT_CONTEXT}。
 */
public class SessionNoteTools {

    @Tool(description = "用于重写当前会话的记忆, 比如有较大的会话改动时使用, 请谨慎使用")
    public void updateNote(String content, ToolContext toolContext) {
        AgentContext context = (AgentContext) toolContext.getContext().get(SessionConfigKeys.AGENT_CONTEXT);
        context.getSessionNotes().replace(
                0,
                context.getSessionNotes().length(),
                content
        );
    }

    @Tool(description = "用于追加当前会话的记忆, 比如有临时性的消息可以使用")
    public void appendNote(@ToolParam(description = "你需要追加的内容") String content, ToolContext toolContext) {
        AgentContext context = (AgentContext) toolContext.getContext().get(SessionConfigKeys.AGENT_CONTEXT);
        context.getSessionNotes().append(content);
    }
}
