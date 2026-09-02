package com.agentcode.hooks;

import com.agentcode.memory.MemoryRecord;
import com.agentcode.memory.MemoryStore;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.hook.AgentHook;
import com.alibaba.cloud.ai.graph.agent.hook.HookPosition;
import com.alibaba.cloud.ai.graph.agent.hook.HookPositions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 长期记忆 Hook：
 * <ul>
 *   <li>beforeAgent：在模型调用前按本轮用户消息召回相关长期记忆，格式化为一条 SystemMessage 注入
 *       （"messages" 键为追加语义，不会覆盖原有消息列表）；召回带 3 秒超时保护，失败/超时降级为空列表。</li>
 *   <li>afterAgent：把本轮 user 消息及其后的全部消息交给记忆库异步抽取落库，hook 立即返回，
 *       绝不阻塞 run 完成路径。</li>
 * </ul>
 * 并发说明：所有状态（召回/抽取的输入）均来自每次调用的入参，无实例级可变状态，多 run 并发安全。
 */
@Slf4j
@HookPositions({HookPosition.BEFORE_AGENT, HookPosition.AFTER_AGENT})
public class MemoryHook extends AgentHook {

    /** 召回超时：ES/embedding 故障时最多等 3 秒，之后降级为空列表，不拖死主链路。 */
    private static final long RECALL_TIMEOUT_SECONDS = 3L;

    /** 审计日志中记忆查询正文的最大长度。 */
    private static final int BRIEF_MAX_LENGTH = 80;

    private static final AtomicInteger POOL_SEQ = new AtomicInteger();

    /** 记忆专用线程池：recall 与 save 共用，daemon 线程不阻碍 JVM 退出。 */
    private static final ExecutorService MEMORY_EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "memory-hook-" + POOL_SEQ.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    });

    private final MemoryStore memoryStore;

    public MemoryHook(MemoryStore memoryStore) {
        this.memoryStore = memoryStore;
    }

    @Override
    public String getName() {
        return "MemoryHook";
    }

    @Override
    @SuppressWarnings("unchecked")
    public CompletableFuture<Map<String, Object>> beforeAgent(OverAllState state, RunnableConfig config) {
        List<Message> messages = (List<Message>) state.value("messages").orElse(new ArrayList<>());

        String userContent = lastUserContent(messages);
        if (userContent == null || userContent.isBlank()) {
            // 本轮没有用户输入（如纯续跑/工具回调），跳过召回。
            return super.beforeAgent(state, config);
        }

        String runId = config.threadId().orElse("-");
        List<MemoryRecord> memories;
        try {
            // search 内部含 embedding + ES 查询，可能阻塞：放记忆专用线程池执行并加超时保护，
            // 失败/超时统一降级为空列表，保证主链路不被记忆故障拖死。
            memories = CompletableFuture
                    .supplyAsync(() -> memoryStore.search(userContent), MEMORY_EXECUTOR)
                    .orTimeout(RECALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .exceptionally(ex -> {
                        log.warn("AUDIT_MEMORY_RECALL_FAILED runId={} query=\"{}\" error={}",
                                runId, brief(userContent), ex.getMessage(), ex);
                        return List.of();
                    })
                    .join();
        } catch (Exception e) {
            log.warn("AUDIT_MEMORY_RECALL_FAILED runId={} query=\"{}\" error={}",
                    runId, brief(userContent), e.getMessage(), e);
            memories = List.of();
        }

        if (memories == null || memories.isEmpty()) {
            return super.beforeAgent(state, config);
        }

        // 召回结果格式化为一条 SystemMessage 注入；"messages" 为追加语义，不影响原有对话。
        StringBuilder sb = new StringBuilder("以下是关于该用户的长期记忆，供参考：\n<long_term_memories>\n");
        for (MemoryRecord memory : memories) {
            if (memory == null || memory.getContent() == null || memory.getContent().isBlank()) {
                continue;
            }
            sb.append("- ").append(memory.getContent().trim()).append('\n');
        }
        sb.append("</long_term_memories>");
        log.info("AUDIT_MEMORY_RECALL runId={} query=\"{}\" injected={}", runId, brief(userContent), memories.size());
        return CompletableFuture.completedFuture(Map.of("messages", List.of(new SystemMessage(sb.toString()))));
    }

    @Override
    @SuppressWarnings("unchecked")
    public CompletableFuture<Map<String, Object>> afterAgent(OverAllState state, RunnableConfig config) {
        Optional<String> runId = config.threadId();
        if (runId.isEmpty() || runId.get().isBlank()) {
            return super.afterAgent(state, config);
        }
        List<Message> messages = (List<Message>) state.value("messages").orElse(new ArrayList<>());

        // 抽取范围 = 本轮（最后一条）user 消息及其后的全部消息。
        // 不能用 beforeAgent 时的 size 做基线：基线已包含当前 user 消息，
        // 以它为界会让"新增消息"只剩 assistant 回复，从而永远跳过保存（实测踩过）。
        int lastUserIdx = -1;
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message message = messages.get(i);
            if (message != null && message.getMessageType() == MessageType.USER) {
                lastUserIdx = i;
                break;
            }
        }
        if (lastUserIdx < 0) {
            // 本轮没有 user 消息（纯续跑/审批恢复）时不值得抽取。
            return super.afterAgent(state, config);
        }

        // 剔除 SYSTEM 消息（含 beforeAgent 注入的召回提示词），避免召回内容被再次抽进记忆库。
        List<Message> newMessages = new ArrayList<>();
        for (Message message : messages.subList(lastUserIdx, messages.size())) {
            if (message != null && message.getMessageType() != MessageType.SYSTEM) {
                newMessages.add(message);
            }
        }
        if (newMessages.isEmpty()) {
            return super.afterAgent(state, config);
        }

        String runIdValue = runId.get();
        // save 内部含 LLM 抽取 + embedding + ES 写入，耗时长：异步执行，hook 立即返回，
        // 绝不阻塞 run 完成路径；内部异常只记审计日志。
        CompletableFuture.runAsync(() -> {
            try {
                memoryStore.save(newMessages, runIdValue);
            } catch (Exception e) {
                log.warn("AUDIT_MEMORY_SAVE_FAILED runId={} error={}", runIdValue, e.getMessage(), e);
            }
        }, MEMORY_EXECUTOR);
        return super.afterAgent(state, config);
    }

    /** 取最后一条 user 消息内容；没有 user 消息则返回 null。 */
    private String lastUserContent(List<Message> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message message = messages.get(i);
            if (message != null && message.getMessageType() == MessageType.USER) {
                return message.getText();
            }
        }
        return null;
    }

    /** 压掉换行并截断，保证一条审计日志只占一行、便于 grep。 */
    private String brief(String text) {
        if (text == null) {
            return "";
        }
        String oneLine = text.replaceAll("\\s+", " ").trim();
        if (oneLine.length() <= BRIEF_MAX_LENGTH) {
            return oneLine;
        }
        return oneLine.substring(0, BRIEF_MAX_LENGTH) + "...(len=" + oneLine.length() + ")";
    }
}
