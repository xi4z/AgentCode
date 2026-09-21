package com.agentcode.audit;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

/**
 * ChatModel 包装器：目前是纯转发，不产生任何日志。
 *
 * ponytail: 审计日志已摘除，等重写审计方案后再补。保留这个壳只是为了不动
 * {@link ChatModelAuditBeanPostProcessor} 的装配；重写审计时应连壳带 BPP 一起删。
 */
public class AuditedChatModel implements ChatModel {

    private final ChatModel delegate;

    public AuditedChatModel(ChatModel delegate, boolean enabled) {
        this.delegate = delegate;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        return delegate.call(prompt);
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return delegate.stream(prompt);
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return delegate.getDefaultOptions();
    }
}
