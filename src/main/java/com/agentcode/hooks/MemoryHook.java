package com.agentcode.hooks;

import com.agentcode.memory.MemoryStore;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.hook.AgentHook;
import com.alibaba.cloud.ai.graph.agent.hook.HookPosition;
import com.alibaba.cloud.ai.graph.agent.hook.HookPositions;
import org.springframework.ai.chat.messages.Message;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

@HookPositions({HookPosition.AFTER_AGENT, HookPosition.BEFORE_AGENT})
public class MemoryHook extends AgentHook {

    private final MemoryStore memoryStore;


    private Integer currIndex = 0; // 存储当前轮会话的开始

    public MemoryHook(MemoryStore memoryStore) {
        this.memoryStore = memoryStore;
    }

    @Override
    public String getName() {
        return "MemoryHook";
    }

    @Override
    public CompletableFuture<Map<String, Object>> beforeAgent(OverAllState state, RunnableConfig config) {
        List<Message> messages = (List<Message>) state.value("messages").orElse(new ArrayList<>());
        if (messages.isEmpty()) {
            return super.beforeAgent(state, config);
        }
        currIndex = messages.size() - 1; // 先记录最尾端的消息地址
        return super.beforeAgent(state, config);
    }

    @Override
    public CompletableFuture<Map<String, Object>> afterAgent(OverAllState state, RunnableConfig config) {
        Optional<String> runId = config.threadId();
        if (runId.isEmpty()) {
            return super.afterAgent(state, config);
        }
        List<Message> messages = (List<Message>) state.value("messages").orElse(new ArrayList<>());
        List<Message> undeterminedMemories = new ArrayList<>(messages.subList(currIndex, messages.size())); // 获取该轮任务所产生的消息
        memoryStore.save(undeterminedMemories, runId.get()); // 进行存储
        // 使用异步方法来
        return super.afterAgent(state, config);
    }
}
