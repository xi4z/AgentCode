package com.agentcode.tools;

import com.agentcode.common.SessionConfigKeys;
import com.agentcode.memory.MemoryRecord;
import com.agentcode.memory.MemoryStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 长期记忆查询工具：把"要不要回忆"的决定权交给 Agent。
 *
 * <p>取代原先 MemoryHook.beforeAgent 的主动注入召回。主动注入在实测中退化成
 * "每轮把大半个记忆库灌进提示词"（库内 17 条时有 37/45 次检索注入了 14 条），
 * 既膨胀上下文又让召回指标失真；改成工具后由模型按需拉取，一次只查它真正关心的问题。
 *
 * <p>检索链路本身带 5 秒超时保护：ES socket-timeout 配的是 30s，不加保护时一次
 * ES 抖动就能把整轮 Agent 卡住；超时/异常一律降级为"查询不可用"，不抛给模型。
 */
@Slf4j
public class MemorySearchTools {

    /** 工具侧召回超时（秒）。 */
    private static final long SEARCH_TIMEOUT_SECONDS = 5L;

    /** 单次返回给模型的记忆条数上限，避免工具结果自己又把上下文撑爆。 */
    private static final int MAX_RESULT_LINES = 8;

    private static final AtomicInteger POOL_SEQ = new AtomicInteger();

    /** 专用线程池：承载可超时的阻塞检索，daemon 不阻碍 JVM 退出。 */
    private static final ExecutorService TOOL_EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "memory-tool-" + POOL_SEQ.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    });

    private final MemoryStore memoryStore;

    public MemorySearchTools(MemoryStore memoryStore) {
        this.memoryStore = memoryStore;
    }

    @Tool(name = "memory_search",
            description = "查询跨会话长期记忆：用户的长期偏好与习惯、项目既有约定与技术栈、以及沉淀下来的通用经验。"
                    + "当回答依赖于「用户以前说过什么 / 偏好什么」，或需要确认项目既有约定时使用本工具；"
                    + "传入一句自然语言问题或一组关键词。查不到结果就直接回答不知道，不要编造记忆。")
    public String searchMemory(
            @ToolParam(description = "要回忆的内容，例如“用户偏好用哪个包管理器装 JS 依赖”") String query,
            ToolContext toolContext) {
        String runId = runId(toolContext);
        if (query == null || query.isBlank()) {
            return "query 不能为空";
        }
        String q = query.trim();
        List<MemoryRecord> memories;
        try {
            memories = CompletableFuture.supplyAsync(() -> memoryStore.search(q), TOOL_EXECUTOR)
                    .orTimeout(SEARCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .join();
        } catch (Exception e) {
            log.warn("AUDIT_MEMORY_TOOL_FAILED runId={} query=\"{}\" error={}", runId, brief(q), e.getMessage());
            return "长期记忆查询暂时不可用（超时或后端异常），请按「没有相关记忆」处理，不要编造。";
        }
        if (memories == null || memories.isEmpty()) {
            log.info("AUDIT_MEMORY_TOOL_SEARCH runId={} query=\"{}\" hits=0", runId, brief(q));
            return "没有找到相关的长期记忆。";
        }

        StringBuilder sb = new StringBuilder("长期记忆命中 ").append(memories.size()).append(" 条：\n");
        int shown = 0;
        for (MemoryRecord memory : memories) {
            if (memory == null || memory.getContent() == null || memory.getContent().isBlank()) {
                continue;
            }
            if (shown++ >= MAX_RESULT_LINES) {
                sb.append("…（其余 ").append(memories.size() - MAX_RESULT_LINES).append(" 条已省略）\n");
                break;
            }
            sb.append("- [").append(memory.getType())
                    .append(" · 置信 ").append(String.format("%.2f", memory.getConfidence()))
                    .append(" · 累计命中 ").append(memory.getHitCount()).append("] ")
                    .append(memory.getContent().trim().replaceAll("\\s+", " "))
                    .append('\n');
        }
        log.info("AUDIT_MEMORY_TOOL_SEARCH runId={} query=\"{}\" hits={} returned={}",
                runId, brief(q), memories.size(), shown);
        return sb.toString().trim();
    }

    private String runId(ToolContext toolContext) {
        if (toolContext == null || toolContext.getContext() == null) {
            return "-";
        }
        Object context = toolContext.getContext().get(SessionConfigKeys.AGENT_CONTEXT);
        return context instanceof com.agentcode.agent.AgentContext agentContext
                && agentContext.getRunId() != null ? agentContext.getRunId() : "-";
    }

    /** 审计日志单行化 + 截断，与 HybridMemoryStore 的 brief() 约定保持一致。 */
    private String brief(String text) {
        if (text == null) {
            return "";
        }
        String oneLine = text.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= 60 ? oneLine : oneLine.substring(0, 60) + "...(len=" + oneLine.length() + ")";
    }
}
