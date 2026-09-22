package com.agentcode.agent;

import com.alibaba.cloud.ai.dashscope.spec.DashScopeApiSpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.openai.api.OpenAiApi;

/**
 * 审计日志出口：只是包了一层的 slf4j，统一审计格式与级别。
 *
 * <p>不持有状态、不碰 config：调用方把要记的数字算好传进来。累计量怎么存、存哪儿，
 * 由调用方决定（当前是 {@code RunnableConfig.context()} 上的 SessionEnum 键）。
 *
 * <pre>
 * 2026-09-22 23:26:03 | RunId xxx | ToolExecution | name: shell ; args: {...}
 * 2026-09-22 23:26:03 | RunId xxx | ToolSuccess   | name: shell ; time: 14ms
 * 2026-09-22 23:26:03 | RunId xxx | ModelCall     | time: 96ms ; totalCalls: 2 ; totalTime: 200ms
 * 2026-09-22 23:26:03 | RunId xxx | ModelUsage    | prompt: 1200 ; completion: 300 ; cached: 1024 ; totalUsage: 1500
 * 2026-09-22 23:26:03 | RunId xxx | SessionStart
 * 2026-09-22 23:26:03 | RunId xxx | SessionEnd    | totalCalls: 2 ; totalTime: 200ms ; wallClock: 473ms ; totalUsage: 1500
 * </pre>
 */
@Slf4j
public class AgentTrace {

    // ==================== 工具 ====================

    public static void toolExecution(String runId, String toolName, String args) {
        log.info(format(runId, "ToolExecution") + "name: {} ; args: {}", toolName, args);
    }

    public static void toolSuccess(String runId, String toolName, long durationMs) {
        log.info(format(runId, "ToolSuccess") + "name: {} ; time: {}ms", toolName, durationMs);
    }

    public static void toolFailure(String runId, String toolName, String exception, long durationMs) {
        log.error(format(runId, "ToolFailure") + "name: {} ; exception: {} ; time: {}ms", toolName, exception, durationMs);
    }

    // ==================== 模型 ====================

    /** 一次模型调用的收口：单次耗时 + 调用方算好的累计值 */
    public static void modelCall(String runId, long durationMs, long totalCalls, long totalTimeMs) {
        log.info(format(runId, "ModelCall") + "time: {}ms ; totalCalls: {} ; totalTime: {}ms",
                durationMs, totalCalls, totalTimeMs);
    }

    /** 一次模型调用的用量 + 累计用量；cache 命中数由调用方按 provider 解析后传入 */
    public static void modelUsage(String runId, Usage usage, long cached, long totalUsage) {
        log.info(format(runId, "ModelUsage") + "prompt: {} ; completion: {} ; cached: {} ; totalUsage: {}",
                token(usage.getPromptTokens()), token(usage.getCompletionTokens()), cached, totalUsage);
    }

    // ==================== 会话 ====================

    public static void sessionStart(String runId) {
        log.info(prefix(runId, "SessionStart"));
    }

    public static void sessionEnd(String runId, long totalCalls, long totalTimeMs,
                                  long wallClockMs, long totalUsage) {
        log.info(format(runId, "SessionEnd") + "totalCalls: {} ; totalTime: {}ms"
                        + " ; wallClock: {}ms ; totalUsage: {}",
                totalCalls, totalTimeMs, wallClockMs, totalUsage);
    }

    // ==================== 工具方法 ====================

    /** cache 命中数只在 provider 的原始用量对象上：DashScope 与 OpenAI 兼容端点字段不同 */
    public static long cachedTokens(Usage usage) {
        Object nativeUsage = usage.getNativeUsage();
        if (nativeUsage instanceof DashScopeApiSpec.TokenUsage dashScopeUsage
                && dashScopeUsage.promptTokenDetailed() != null) {
            return token(dashScopeUsage.promptTokenDetailed().cachedTokens());
        }
        if (nativeUsage instanceof OpenAiApi.Usage openAiUsage && openAiUsage.promptTokensDetails() != null) {
            return token(openAiUsage.promptTokensDetails().cachedTokens());
        }
        return 0L;
    }

    /** 统一前缀：时间 | RunId xxx | 事件名 */
    private static String prefix(String runId, String event) {
        return java.time.LocalDateTime.now().format(TIME) + " | RunId " + (runId == null ? "-" : runId)
                + " | " + event;
    }

    /** 带字段的事件：前缀 + " | "，字段之间用 " ; " 分隔 */
    private static String format(String runId, String event) {
        return prefix(runId, event) + " | ";
    }

    private static long token(Integer tokens) {
        return tokens == null ? 0L : tokens;
    }

    private static final java.time.format.DateTimeFormatter TIME =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
}
