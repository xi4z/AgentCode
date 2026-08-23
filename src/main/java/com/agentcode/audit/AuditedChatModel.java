package com.agentcode.audit;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * ChatModel 审计包装器。
 *
 * 记录每次 AI 调用的运行时数据：
 * model / durationMs / promptMessages / chunks / responseLength / toolCalls /
 * promptTokens / completionTokens / totalTokens。
 *
 * 只包装真实 AI Provider 的 ChatModel，测试中的 mock ChatModel 不会被包装，
 * 避免影响现有测试对 ChatModel 类型的直接断言。
 */
@Slf4j
public class AuditedChatModel implements ChatModel {

    private final ChatModel delegate;
    private final boolean enabled;

    public AuditedChatModel(ChatModel delegate, boolean enabled) {
        this.delegate = delegate;
        this.enabled = enabled;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        long start = System.nanoTime();
        ChatResponse response = delegate.call(prompt);
        if (enabled) {
            long durationMs = (System.nanoTime() - start) / 1_000_000;
            log.info(
                    "AUDIT_AI_CALL model={} durationMs={} promptMessages={} responseLength={} toolCalls={} promptTokens={} completionTokens={} totalTokens={}",
                    resolveModel(response, prompt),
                    durationMs,
                    prompt.getInstructions().size(),
                    responseLength(response),
                    toolCalls(response),
                    promptTokens(response),
                    completionTokens(response),
                    totalTokens(response)
            );
        }
        return response;
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        long start = System.nanoTime();
        AtomicInteger chunks = new AtomicInteger();
        AtomicInteger contentLength = new AtomicInteger();
        AtomicInteger toolCalls = new AtomicInteger();

        return delegate.stream(prompt)
                .doOnNext(response -> {
                    chunks.incrementAndGet();
                    contentLength.addAndGet(responseLength(response));
                    toolCalls.addAndGet(toolCalls(response));
                })
                .doOnComplete(() -> {
                    if (enabled) {
                        log.info(
                                "AUDIT_AI_STREAM model={} durationMs={} promptMessages={} chunks={} responseLength={} toolCalls={} promptTokens={} completionTokens={} totalTokens={}",
                                resolveModel(null, prompt),
                                (System.nanoTime() - start) / 1_000_000,
                                prompt.getInstructions().size(),
                                chunks.get(),
                                contentLength.get(),
                                toolCalls.get(),
                                0,
                                0,
                                0
                        );
                    }
                })
                .doOnError(error -> {
                    if (enabled) {
                        log.warn(
                                "AUDIT_AI_STREAM_ERROR model={} durationMs={} promptMessages={} error={}",
                                resolveModel(null, prompt),
                                (System.nanoTime() - start) / 1_000_000,
                                prompt.getInstructions().size(),
                                error.getMessage()
                        );
                    }
                });
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return delegate.getDefaultOptions();
    }

    private String resolveModel(ChatResponse response, Prompt prompt) {
        String responseModel = model(response);
        if (responseModel != null && !"null".equals(responseModel)) {
            return responseModel;
        }
        if (prompt.getOptions() != null && prompt.getOptions().getModel() != null) {
            return prompt.getOptions().getModel();
        }
        if (delegate.getDefaultOptions() != null && delegate.getDefaultOptions().getModel() != null) {
            return delegate.getDefaultOptions().getModel();
        }
        return null;
    }

    private String model(ChatResponse response) {
        if (response != null && response.getMetadata() != null) {
            return String.valueOf(response.getMetadata().getModel());
        }
        return null;
    }

    private int responseLength(ChatResponse response) {
        if (response == null || response.getResults() == null) {
            return 0;
        }
        return response.getResults().stream()
                .map(Generation::getOutput)
                .filter(java.util.Objects::nonNull)
                .mapToInt(output -> {
                    String content = output.getText();
                    return content == null ? 0 : content.length();
                })
                .sum();
    }

    private int toolCalls(ChatResponse response) {
        if (response == null || response.getResults() == null) {
            return 0;
        }
        return response.getResults().stream()
                .map(Generation::getOutput)
                .filter(java.util.Objects::nonNull)
                .mapToInt(output -> output.getToolCalls() == null ? 0 : output.getToolCalls().size())
                .sum();
    }

    private Integer promptTokens(ChatResponse response) {
        Usage usage = usage(response);
        return usage == null ? null : usage.getPromptTokens();
    }

    private Integer completionTokens(ChatResponse response) {
        Usage usage = usage(response);
        return usage == null ? null : usage.getCompletionTokens();
    }

    private Integer totalTokens(ChatResponse response) {
        Usage usage = usage(response);
        return usage == null ? null : usage.getTotalTokens();
    }

    private Usage usage(ChatResponse response) {
        if (response == null || response.getMetadata() == null) {
            return null;
        }
        return response.getMetadata().getUsage();
    }
}
