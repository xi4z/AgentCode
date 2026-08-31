package com.agentcode.metrics;

import com.agentcode.common.SessionConfigKeys;
import com.agentcode.agent.AgentContext;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallResponse;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolInterceptor;
import lombok.extern.slf4j.Slf4j;

/**
 * Logs tool-call metrics with runId so quantitative tests can compute
 * tool success/failure rates from DEBUG logs.
 */
@Slf4j
public class ToolMetricsInterceptor extends ToolInterceptor {
    private static final int MAX_RESULT_LENGTH = 500;

    @Override
    public String getName() {
        return "tool_metrics_interceptor";
    }

    @Override
    public ToolCallResponse interceptToolCall(ToolCallRequest request, ToolCallHandler handler) {
        long start = System.nanoTime();
        String runId = resolveRunId(request);
        try {
            ToolCallResponse response = handler.call(request);
            long durationMs = (System.nanoTime() - start) / 1_000_000;
            boolean isError = response != null && response.isError();
            String status = response == null ? "NULL" : response.getStatus();
            String result = response == null ? null : response.getResult();
            log.info("AUDIT_TOOL_METRICS runId={} tool={} durationMs={} status={} error={} result={}",
                    runId,
                    request.getToolName(),
                    durationMs,
                    status,
                    isError,
                    abbreviate(result));
            return response;
        } catch (Exception e) {
            long durationMs = (System.nanoTime() - start) / 1_000_000;
            log.warn("AUDIT_TOOL_METRICS runId={} tool={} durationMs={} status=EXCEPTION error=true result={}",
                    runId, request.getToolName(), durationMs, e.getMessage());
            throw e;
        }
    }

    private String resolveRunId(ToolCallRequest request) {
        return request.getExecutionContext()
                .flatMap(ctx -> ctx.threadId())
                .orElseGet(() -> {
                    Object context = request.getContext().get(SessionConfigKeys.AGENT_CONTEXT);
                    if (context instanceof AgentContext agentContext) {
                        return agentContext.getRunId();
                    }
                    return null;
                });
    }

    private String abbreviate(String text) {
        if (text == null) {
            return null;
        }
        String oneLine = text.replaceAll("\\s+", " ").trim();
        if (oneLine.length() <= MAX_RESULT_LENGTH) {
            return oneLine;
        }
        return oneLine.substring(0, MAX_RESULT_LENGTH) + "...truncated";
    }
}