package com.agentcode.context;

import lombok.*;

@Data
@Builder
public class AgentContext {
    /**
     * AgentContext 流程:
     * 在从本地持久化或数据库拿到数据后: AgentContextModel
     * 注入 systemPrompt, globalContext 与 projectContext
     * sessionNotes , workspace 由 model 注入
     */

    final String runId;
    String goal;
    final String workspace;

    String sessionNotes; // 会话笔记, 用于记录用户在本次会话中的主要要求或关键的短期记忆, 防止遗忘, 此字段应移入数据库存储或由本地持久化

    public String systemPrompt(String systemPrompt) {
        StringBuilder sb = new StringBuilder("system");
        return sb.toString();
    }

    /**
     * TODO 解析项目的提示词
     * @param workspace
     */
    private String parseProjectContext(String workspace) {
        // TODO 从Properties 中拉取Glob system project 各自的提示词来组装

        // TODO 将当前对象的 sessionNotes 拉下来组装
        return new String(workspace);
    }
}
