package com.agentcode.context;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AgentContext {
    final String runId;
    String goal;
    String result;
    String systemPrompt;
    String workspace;


    String globalContext;
    String projectContext;
    String sessionNotes;

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
