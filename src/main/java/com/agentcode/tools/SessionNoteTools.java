package com.agentcode.tools;

import com.agentcode.common.SessionConfigKeys;
import com.agentcode.agent.AgentContext;
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
        StringBuffer notes = context.getSessionNotes();
        // M4 修复：先取 length 再 replace 两步之间并行工具可能 append 导致长度变化（越界异常），
        // StringBuffer 自身方法以实例为监视器锁，这里用同一把锁组合保证"清空重写"整体原子
        synchronized (notes) {
            notes.replace(0, notes.length(), content);
        }
    }

    @Tool(description = "用于追加当前会话的记忆, 比如有临时性的消息可以使用")
    public void appendNote(@ToolParam(description = "你需要追加的内容") String content, ToolContext toolContext) {
        AgentContext context = (AgentContext) toolContext.getContext().get(SessionConfigKeys.AGENT_CONTEXT);
        // M4: sessionNotes 已是 StringBuffer，单次 append 本身线程安全
        context.getSessionNotes().append(content);
    }
}
