package com.agentcode.audit;

import com.agentcode.entity.AuditLog;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * ChatModel 审计包装器。
 *
 * 记录每次 AI 调用的运行时数据：
 * model / durationMs / promptMessages / chunks / responseLength / toolCalls /
 * promptTokens / completionTokens / totalTokens。
 *
 * 审计记录会同时写入日志和 MySQL 的 audit_log 表。
 *
 * 只包装真实 AI Provider 的 ChatModel，测试中的 mock ChatModel 不会被包装，
 * 避免影响现有测试对 ChatModel 类型的直接断言。
 */
@Slf4j
public class AuditedChatModel implements ChatModel {

    private final ChatModel delegate;
    private final boolean enabled;
    private final AuditLogRepository auditLogRepository;

    public AuditedChatModel(ChatModel delegate, boolean enabled) {
        this(delegate, enabled, null);
    }

    public AuditedChatModel(ChatModel delegate, boolean enabled, AuditLogRepository auditLogRepository) {
        this.delegate = delegate;
        this.enabled = enabled;
        this.auditLogRepository = auditLogRepository;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        long start = System.nanoTime();
        ChatResponse response = delegate.call(prompt);
        if (enabled) {
            long durationMs = (System.nanoTime() - start) / 1_000_000;
            Integer promptTokens = promptTokens(response);
            Integer completionTokens = completionTokens(response);
            Integer totalTokens = totalTokens(response);
            int responseLength = responseLength(response);
            int toolCalls = toolCalls(response);
            log.info(
                    "AUDIT_AI_CALL model={} durationMs={} promptMessages={} responseLength={} toolCalls={} promptTokens={} completionTokens={} totalTokens={}",
                    resolveModel(response, prompt),
                    durationMs,
                    prompt.getInstructions().size(),
                    responseLength,
                    toolCalls,
                    promptTokens,
                    completionTokens,
                    totalTokens
            );
            persist("AI_CALL", resolveModel(response, prompt), durationMs,
                    prompt.getInstructions().size(), null, responseLength, toolCalls,
                    promptTokens, completionTokens, totalTokens, null);
        }
        return response;
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        long start = System.nanoTime();
        AtomicInteger chunks = new AtomicInteger();
        AtomicInteger contentLength = new AtomicInteger();
        AtomicInteger toolCalls = new AtomicInteger();
        AtomicReference<Usage> lastUsage = new AtomicReference<>();

        return delegate.stream(prompt)
                .doOnNext(response -> {
                    chunks.incrementAndGet();
                    contentLength.addAndGet(responseLength(response));
                    toolCalls.addAndGet(toolCalls(response));
                    Usage usage = usage(response);
                    if (usage != null) {
                        lastUsage.set(usage);
                    }
                })
                .doOnComplete(() -> {
                    if (enabled) {
                        long durationMs = (System.nanoTime() - start) / 1_000_000;
                        Integer promptTokens = lastUsage.get() == null ? null : lastUsage.get().getPromptTokens();
                        Integer completionTokens = lastUsage.get() == null ? null : lastUsage.get().getCompletionTokens();
                        Integer totalTokens = lastUsage.get() == null ? null : lastUsage.get().getTotalTokens();
                        log.info(
                                "AUDIT_AI_STREAM model={} durationMs={} promptMessages={} chunks={} responseLength={} toolCalls={} promptTokens={} completionTokens={} totalTokens={}",
                                resolveModel(null, prompt),
                                durationMs,
                                prompt.getInstructions().size(),
                                chunks.get(),
                                contentLength.get(),
                                toolCalls.get(),
                                promptTokens,
                                completionTokens,
                                totalTokens
                        );
                        persist("AI_STREAM", resolveModel(null, prompt), durationMs,
                                prompt.getInstructions().size(), chunks.get(), contentLength.get(),
                                toolCalls.get(), promptTokens, completionTokens, totalTokens, null);
                    }
                })
                .doOnError(error -> {
                    if (enabled) {
                        long durationMs = (System.nanoTime() - start) / 1_000_000;
                        log.warn(
                                "AUDIT_AI_STREAM_ERROR model={} durationMs={} promptMessages={} error={}",
                                resolveModel(null, prompt),
                                durationMs,
                                prompt.getInstructions().size(),
                                error.getMessage()
                        );
                        persist("AI_STREAM_ERROR", resolveModel(null, prompt), durationMs,
                                prompt.getInstructions().size(), chunks.get(), contentLength.get(),
                                toolCalls.get(), null, null, null, error.getMessage());
                    }
                });
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return delegate.getDefaultOptions();
    }

    private void persist(String type, String model, long durationMs, int promptMessages,
                         Integer chunks, int responseLength, int toolCalls,
                         Integer promptTokens, Integer completionTokens, Integer totalTokens,
                         String error) {
        if (auditLogRepository == null) {
            return;
        }
        AuditLog auditLog = new AuditLog();
        auditLog.setType(type);
        auditLog.setModel(model);
        auditLog.setDurationMs(durationMs);
        auditLog.setPromptMessages(promptMessages);
        auditLog.setChunks(chunks);
        auditLog.setResponseLength(responseLength);
        auditLog.setToolCalls(toolCalls);
        auditLog.setPromptTokens(promptTokens);
        auditLog.setCompletionTokens(completionTokens);
        auditLog.setTotalTokens(totalTokens);
        auditLog.setError(error);
        auditLogRepository.save(auditLog);
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