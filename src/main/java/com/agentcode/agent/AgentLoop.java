package com.agentcode.agent;

import com.agentcode.common.model.ContextStatus;
import com.agentcode.context.AgentContext;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AgentLoop {
    private final ChatModel chatModel;
    private final MemorySaver memorySaver;

    public AgentResult run(AgentContext context) {
        // TODO 可以根据设定注入可用工具
        ReactAgent reactAgent = ReactAgent.builder()
                .model(chatModel)
                .saver(memorySaver)
                .systemPrompt(context.systemPrompt())
                .build();

        // 在重新 run 之后, 修改 context 状态
        RunnableConfig config = RunnableConfig.builder()
                .threadId(context.getRunId()) // 获取数据
                .build();

        try{
            AssistantMessage response = reactAgent.call(
                    context.getGoal(), config
            );
            return new AgentResult(
                    "success",
                    response.getText(),
                    "无",
                    0 // TODO 以后实现步数加入
            );
        } catch (GraphRunnerException e) {
            throw new RuntimeException(e);
        }
    }
}
