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

    /**
     * Reactor Context 中承载当前 run 的 key；由 {@code AgentSession.run} 的
     * {@code contextWrite} 注入，用于把 AI 审计日志关联到具体 run。
     */
    public static final String RUN_ID_CONTEXT_KEY = "agentcode.runId";

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
        // 流式 usage 通常只在最后一个 chunk 出现，也可能分片上报；
        // 逐字段取最大值聚合，避免被中间的空 chunk 覆盖成 0。
        AtomicInteger promptTokens = new AtomicInteger();
        AtomicInteger completionTokens = new AtomicInteger();
        AtomicInteger totalTokens = new AtomicInteger();

        // deferContextual 读取 AgentSession 通过 contextWrite 注入的 runId，
        // 让 AI 审计日志可直接按 runId 关联（并发场景下比时间窗口归因更可靠）。
        return Flux.deferContextual(ctx -> {
            String runId = ctx.getOrDefault(RUN_ID_CONTEXT_KEY, "-");
            return delegate.stream(prompt)
                    .doOnNext(response -> {
                        chunks.incrementAndGet();
                        contentLength.addAndGet(responseLength(response));
                        toolCalls.addAndGet(toolCalls(response));
                        accumulateUsageMax(usage(response), promptTokens, completionTokens, totalTokens);
                    })
                    .doOnComplete(() -> {
                        if (enabled) {
                            log.info(
                                    "AUDIT_AI_STREAM runId={} model={} durationMs={} promptMessages={} chunks={} responseLength={} toolCalls={} promptTokens={} completionTokens={} totalTokens={}",
                                    runId,
                                    resolveModel(null, prompt),
                                    (System.nanoTime() - start) / 1_000_000,
                                    prompt.getInstructions().size(),
                                    chunks.get(),
                                    contentLength.get(),
                                    toolCalls.get(),
                                    promptTokens.get(),
                                    completionTokens.get(),
                                    totalTokens.get()
                            );
                        }
                    })
                    .doOnError(error -> {
                        if (enabled) {
                            log.warn(
                                    "AUDIT_AI_STREAM_ERROR runId={} model={} durationMs={} promptMessages={} error={}",
                                    runId,
                                    resolveModel(null, prompt),
                                    (System.nanoTime() - start) / 1_000_000,
                                    prompt.getInstructions().size(),
                                    error.getMessage()
                            );
                        }
                    });
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

    /**
     * 流式聚合 usage：逐字段取最大值。
     *
     * <p>OpenAI 兼容的流式返回常常只在最后一个 chunk 带 usage，
     * 中间 chunk 的 usage 为 0/null；取最大值既能拿到最终值，
     * 也能兼容分片累加上报的实现，避免被后续空值覆盖成 0。
     */
    private void accumulateUsageMax(Usage usage,
                                    AtomicInteger promptTokens,
                                    AtomicInteger completionTokens,
                                    AtomicInteger totalTokens) {
        if (usage == null) {
            return;
        }
        mergeMax(usage.getPromptTokens(), promptTokens);
        mergeMax(usage.getCompletionTokens(), completionTokens);
        mergeMax(usage.getTotalTokens(), totalTokens);
    }

    private void mergeMax(Integer value, AtomicInteger accumulator) {
        if (value != null) {
            accumulator.accumulateAndGet(value, Math::max);
        }
    }
}
