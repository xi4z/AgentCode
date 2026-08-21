package com.agentcode.tools;

import lombok.Builder;
import org.springframework.ai.tool.annotation.Tool;

@Builder
public class SessionNoteTools {
    final String runId; // 用于访问指定会话的 notes

    @Tool(description = "用于重写当前会话的记忆, 比如有较大的会话改动时使用")
    public String updateNote(String content) {

    }

    @Tool(description = "用于追加当前会话的记忆, 比如有临时性的消息可以使用")
    public String appendNote(String content){


    }

    @Tool(description = "用于获取当前 note 的全量记忆")
    public String getAllNote(){


    }
}
