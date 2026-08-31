package com.agentcode.memory;


import org.springframework.ai.chat.messages.Message;

import java.util.List;

public interface MemoryStore {

    /**
     * 给定近期的一系列记忆和 runId
     * 判断是否有记忆存入或者有记忆命中至记忆库
     * @param messages
     * @param runId
     */
    void save(List<Message> messages, String runId);

    /**
     * 给定想要回忆的内容
     *
     * @param content
     * @return
     */
    List<MemoryRecord> search(String content);
}
