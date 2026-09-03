package com.agentcode.hooks;

import com.agentcode.memory.MemoryStore;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.hook.AgentHook;
import com.alibaba.cloud.ai.graph.agent.hook.HookPosition;
import com.alibaba.cloud.ai.graph.agent.hook.HookPositions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 长期记忆 Hook：<b>只负责写入侧</b>，不再做主动召回。
 *
 * <p>为什么去掉主动召回（实测驱动）：原先 beforeAgent 每轮做分层混排召回，把命中记忆整段注入
 * 一条 SystemMessage。实测库内仅 17 条记忆时，45 次检索里有 37 次注入了 14 条——等于把八成库
 * 灌进提示词。后果是 prompt 膨胀、无关记忆稀释注意力，而且"召回率 100%"完全失真：答对不是
 * 因为检索准，而是因为全都塞进去了。
 *
 * <p>现在改为拉取式：Agent 通过 {@code memory_search} 工具按需查询长期记忆
 * （见 {@link com.agentcode.tools.MemorySearchTools}），由模型自己决定何时回忆。
 *
 * <p>两个钩子位的职责：
 * <ul>
 *   <li>beforeAgent：只记录"本轮记忆起点"——最后一条 user 消息的下标，供 afterAgent 切片。
 *       不再触碰 embedding / ES，因此也就不再需要召回超时保护。</li>
 *   <li>afterAgent：按起点切出本轮新增消息，异步抽取落库，立即返回，绝不阻塞 run 完成路径。</li>
 * </ul>
 *
 * <p>并发说明：起点记录按 runId 隔离（有界 LRU），其余状态均来自调用入参，多 run 并发安全。
 */
@Slf4j
@HookPositions({HookPosition.BEFORE_AGENT, HookPosition.AFTER_AGENT})
public class MemoryHook extends AgentHook {

    private static final AtomicInteger POOL_SEQ = new AtomicInteger();

    /** 记忆专用线程池：save 走这里，daemon 线程不阻碍 JVM 退出。 */
    private static final ExecutorService MEMORY_EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "memory-hook-" + POOL_SEQ.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    });

    /** 起点缓存容量上限：正常都会被 afterAgent 取走，上限只为兜住异常路径不涨内存。 */
    private static final int START_INDEX_CAPACITY = 512;

    /**
     * runId -> 本轮记忆起点（最后一条 user 消息下标）。
     * <p>
     * 存在 hook 里而不是写进 graph state：state 的自定义键没有注册更新策略，
     * 写进去不保证可见（本仓库其它 hook 只读 iterations/tokenUsage 这类既有键）。
     */
    private final Map<String, Integer> memoryStartIndex = Collections.synchronizedMap(
            new LinkedHashMap<>(64, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Integer> eldest) {
                    return size() > START_INDEX_CAPACITY;
                }
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
        int start = lastUserIndex(messages);
        if (start >= 0) {
            config.threadId().ifPresent(runId -> memoryStartIndex.put(runId, start));
        }
        if (log.isDebugEnabled()) {
            log.debug("AUDIT_MEMORY_TRACE_START runId={} messages={} startIndex={}",
                    config.threadId().orElse("-"), messages.size(), start);
        }
        // 只做记录，不返回任何 state 更新：召回已改由 memory_search 工具按需触发
        return super.beforeAgent(state, config);
    }

    @Override
    @SuppressWarnings("unchecked")
    public CompletableFuture<Map<String, Object>> afterAgent(OverAllState state, RunnableConfig config) {
        Optional<String> runId = config.threadId();
        if (runId.isEmpty() || runId.get().isBlank()) {
            return super.afterAgent(state, config);
        }
        List<Message> messages = (List<Message>) state.value("messages").orElse(new ArrayList<>());

        // 优先用 beforeAgent 记下的起点；取不到就自己扫一遍兜底
        // （审批恢复、纯续跑等路径可能没经过 beforeAgent）。
        Integer recorded = memoryStartIndex.remove(runId.get());
        int start = recorded == null ? lastUserIndex(messages) : recorded;
        // 中途发生摘要/压缩时，记下的下标可能已越过当前列表长度，退回重新扫描。
        if (start < 0 || start >= messages.size()) {
            start = lastUserIndex(messages);
        }
        if (start < 0) {
            // 本轮没有 user 消息（纯续跑/审批恢复）时不值得抽取。
            return super.afterAgent(state, config);
        }

        // 起点必须是"最后一条 user 消息"而不是 beforeAgent 时的 messages.size()：
        // 以 size 为基线会把当前这条 user 消息排除在外，"新增消息"只剩 assistant 回复，
        // 于是永远跳过保存（实测踩过）。
        // 同时剔除 SYSTEM 消息（含历史注入的召回提示词），避免把召回内容再抽进记忆库。
        List<Message> newMessages = new ArrayList<>();
        for (Message message : messages.subList(start, messages.size())) {
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

    /** 取最后一条 user 消息的下标；没有则返回 -1。 */
    private int lastUserIndex(List<Message> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message message = messages.get(i);
            if (message != null && message.getMessageType() == MessageType.USER) {
                return i;
            }
        }
        return -1;
    }
}
