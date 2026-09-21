package com.agentcode.agent.interceptor;

import com.agentcode.context.AgentContext;
import com.agentcode.session.SessionEnum;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelInterceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.openai.api.OpenAiApi;

@Slf4j
public class ModelPerformanceInterceptor extends ModelInterceptor {
    @Override
    public ModelResponse interceptModel(ModelRequest request, ModelCallHandler handler) {
        AgentContext currContext = (AgentContext) request.getContext().get(SessionEnum.AGENT_CONTEXT.getCode());
        String threadId = currContext.getRunId();
        // 请求前记录
        log.info("RunId: {} | 发送请求到模型: {} 条消息",threadId, request.getMessages().size());

        long startTime = System.currentTimeMillis();

        // 执行实际调用
        ModelResponse response = handler.call(request);
        ChatResponseMetadata metadata = response.getChatResponse().getMetadata();
        Token token = Token.of(metadata.getUsage());
        // 响应后记录
        long duration = System.currentTimeMillis() - startTime;
        log.info("RunId: {} | 模型响应耗时: {} ms | 消耗 Token in: {} / out: {} / cache: {}",threadId, duration, token.in, token.out, token.cache);

        return response;
    }

    @Override
    public String getName() {
        return "ModelPerformanceInterceptor";
    }

    @RequiredArgsConstructor
    private static class Token{
        public final Integer in;
        public final Integer out;
        public final Integer cache;

        public static Token of(Usage usage){
            if (usage.getNativeUsage() instanceof OpenAiApi.Usage use){

                return new Token(usage.getPromptTokens(), usage.getCompletionTokens(), use.promptTokensDetails().cachedTokens());
            }
            return new Token(0, 0, 0);
        }
    }
}
