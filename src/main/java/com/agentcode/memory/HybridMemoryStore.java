package com.agentcode.memory;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class HybridMemoryStore implements MemoryStore {

    private final ReactAgent memoryAgent;

    @Override
    public void save(List<Message> messages, String runId) {
        // save 时, 先进行一次搜索


    }

    @Override
    public List<MemoryRecord> search(String content) {

        return null;
    }

    /**
     * 真正的存记忆方法
     * @param memory 需要存储的记忆
     * @param runId 会话ID
     * @param memoryType 记忆类型, 到指定阈值后可以升级
     */
    private void save(String memory, String runId, MemoryRecord.MemoryType memoryType) {


    }

    /**
     * 对记忆进行升级或其他
     * @param memory
     */
    private void updateMemory(MemoryRecord memory){

    }
    /**
     * 尝试命中一次已经有过的记忆
     * @param memory
     * @return
     */
    private boolean tryHit(String memory){
        // TODO: 查找相似记忆并执行命中强化；暂未实现，先返回 false 表示未命中
        return false;
    }


}
