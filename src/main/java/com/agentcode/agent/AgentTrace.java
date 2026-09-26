package com.agentcode.agent;

import com.alibaba.cloud.ai.dashscope.spec.DashScopeApiSpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.openai.api.OpenAiApi;

/**
 * 审计日志出口：只是包了一层的 slf4j，统一审计格式与级别。
 *
 * <p>不持有状态、不碰 config：调用方把要记的东西算好传进来。累计量怎么存、存哪儿，
 * 由调用方决定（当前是 {@code RunnableConfig.context()} 上的 SessionEnum 键，
 * 以及 {@code AgentContext} 上的 run 起始时刻）。
 *
 * <pre>
 * 2026-09-22 23:26:03 | RunId xxx | ToolExecution | name: shell ; args: {...}
 * 2026-09-22 23:26:03 | RunId xxx | ToolSuccess   | name: shell ; time: 14ms
 * 2026-09-22 23:26:03 | RunId xxx | ToolFailure   | name: shell ; exception: ... ; time: 14ms
 * 2026-09-22 23:26:03 | RunId xxx | ModelCall     | time: 96ms ; totalCalls: 2 ; totalTime: 200ms
 * 2026-09-22 23:26:03 | RunId xxx | ModelUsage    | prompt: 1200 ; completion: 300 ; cached: 1024 ; totalUsage: 1500
 * 2026-09-22 23:26:03 | RunId xxx | Run B | modelCalls: 0 ; wallClock: 0ms ; tokenUsage: 0
 * 2026-09-22 23:26:03 | RunId xxx | Run E | modelCalls: 2 ; wallClock: 473ms ; tokenUsage: 1500
 * 2026-09-22 23:26:03 | RunId xxx | Run I | modelCalls: 2 ; wallClock: 1180ms ; tokenUsage: 1500  （停在审批中断点上）
 * 2026-09-22 23:26:03 | RunId xxx | Approval S | name: shell ; callId: call_1 ; decision: ASK ; reason: deny-or-unknown ; command: ls
 * 2026-09-22 23:26:03 | RunId xxx | Approval Q | name: - ; callId: - ; pending: 1 ; shell
 * 2026-09-22 23:26:03 | RunId xxx | Approval A | name: shell ; callId: call_1 ; decision: REJECTED ; result-to-model: rejected-by-user
 * 2026-09-22 23:26:03 | RunId xxx | Approval X | name: - ; callId: - ; resume: pending-cleared
 * </pre>
 *
 * <p>审计正文一律 ASCII：这台机器上 logback 落文件用的是平台默认字符集（非 UTF-8），
 * 中文会被写成 "?"，日志里读不回来。
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

    // ==================== 轮次 ====================

    /**
     * 一轮 run 的起止。phase: B=开始 / E=正常结束 / I=停在审批中断点上。
     *
     * <p>E 由 {@code AgentPerformanceHook.afterAgent} 出（走完了 end 节点）；
     * I 由 {@code AgentSession} 在流终止时补，因为中断那一轮 afterAgent 不会执行。
     */
    public static void runEvent(String runId, char phase, long modelCalls, long wallClockMs, long tokenUsage) {
        log.info(format(runId, "Run " + phase) + "modelCalls: {} ; wallClock: {}ms ; tokenUsage: {}",
                modelCalls, wallClockMs, tokenUsage);
    }

    // ==================== 审批 ====================

    /**
     * 工具审批的一个事件点：事件代号 + 工具 + 依据/决定。
     *
     * <p>S=自动评估（放行或拒绝）/ Q=转人工(发 permission.requested，含没批完的打回) /
     * A=用户答复 / X=恢复执行。S 那类把命中原因写进 reason（path / allowlist / session-cache /
     * deny-or-unknown），A 那类把决定写进 reason（APPROVED / APPROVE_ALL / REJECTED / EDITED）。
     */
    public static void approval(String runId, char event, String toolName, String callId, String reason) {
        log.info(format(runId, "Approval " + event) + "name: {} ; callId: {} ; {}",
                toolName == null ? "-" : toolName, callId == null ? "-" : callId, reason);
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
