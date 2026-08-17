package com.agentcode.context;

import com.agentcode.common.model.ContextStatus;
import com.agentcode.context.block.TextBlock;
import com.agentcode.context.block.ToolResultBlock;
import lombok.Builder;
import lombok.Data;
import org.springframework.ai.chat.messages.Message;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
public class AgentContext {
    // TODO: runId / goal / step / status / result / messages / maxSteps
    final String runId;
    String goal;

    int step = 0;
    int maxSteps; // 控制上下文

    ContextStatus status;
    String result;

    String systemPrompt;

    // 会话上下文, 包括对全局记忆, 项目记忆, 还有在会话中遇见的短期记忆
    // 在每轮开始时都应更新一次
    String globalContext;
    String projectContext;
    String sessionNotes;

    // 拼接 system prompt
    public String systemPrompt() {
        StringBuilder sb = new StringBuilder();
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
