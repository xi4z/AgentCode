package com.agentcode.context;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;

@Data
@Builder
@RequiredArgsConstructor
@AllArgsConstructor
public class AgentContext {
    /**
     * AgentContext 流程:
     * 在从本地持久化或数据库拿到数据后: AgentContextModel
     * 注入 systemPrompt, globalContext 与 projectContext
     * sessionNotes , workspace 由 model 注入
     */
    public enum Status{
        FREE, // 当前会话没有在运行
        RUNNING, // 当前会话正在运行
        INTERRUPTED // 当前会话被中断, 出现这种状态的原因通常是 Agent 正在等待用户审批
    }

    final String runId;
    String goal;
    Status status;

    String result;
    String workspace;


    // 系统提示词 应固定且不应更改
    final String systemPrompt;
    String globalContext; // 全局提示词, 这个提示词由用户自定义且挂在到用户目录
    String projectContext; // 项目提示词, 可以在项目内自定义为agent.md, 读取即可
    String sessionNotes; // 会话笔记, 用于记录用户在本次会话中的主要要求或关键的短期记忆, 防止遗忘, 此字段应移入数据库存储或由本地持久化

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

    /**
     * 注入上下文
     * @param workspace
     */
    private void injectContext(String workspace) {}
}
