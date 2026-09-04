package com.agentcode.metrics;

import com.agentcode.agent.AgentContext;
import com.agentcode.common.SessionConfigKeys;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallResponse;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolInterceptor;
import lombok.extern.slf4j.Slf4j;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Audits tool calls and enforces a model-visible secret redaction boundary.
 * Redaction is performed before the response is returned to the agent; logging
 * is also redacted so credentials cannot escape through either channel.
 */
@Slf4j
public class ToolMetricsInterceptor extends ToolInterceptor {
    private static final int MAX_RESULT_LENGTH = 500;
    private static final Pattern SECRET_PATTERN = Pattern.compile(
            "(?i)(\\b(?:token|api[_-]?key|access[_-]?key|secret|password|passwd)\\s*[=:]\\s*)([^\\s,;]+)"
                    + "|(-----BEGIN (?:RSA |OPENSSH |EC |DSA )?PRIVATE KEY-----)([\\s\\S]*?)(-----END (?:RSA |OPENSSH |EC |DSA )?PRIVATE KEY-----)"
                    + "|(\\bAKIA[0-9A-Z]{12,})(?![A-Za-z0-9])");
    private static final Pattern NON_ZERO_EXIT = Pattern.compile("(?is)(?:^|\\s)exit\\s*=\\s*(-?[0-9]+)\\b");

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
            String rawResult = response == null ? null : response.getResult();
            String result = sanitize(rawResult);
            boolean isError = isFailure(response, rawResult);
            String status = response == null ? "NULL" : response.getStatus();
            log.info("AUDIT_TOOL_METRICS runId={} tool={} durationMs={} status={} error={} result={}",
                    runId, request.getToolName(), durationMs, status, isError, abbreviate(result));
            return sanitizedResponse(response, result, isError);
        } catch (Exception e) {
            long durationMs = (System.nanoTime() - start) / 1_000_000;
            log.warn("AUDIT_TOOL_METRICS runId={} tool={} durationMs={} status=EXCEPTION error=true result={}",
                    runId, request.getToolName(), durationMs, sanitize(e.getMessage()));
            throw e;
        }
    }

    private ToolCallResponse sanitizedResponse(ToolCallResponse response, String result, boolean isError) {
        if (response == null) return null;
        if (Objects.equals(response.getResult(), result) && (!isError || response.isError())) return response;
        return new ToolCallResponse(result, response.getToolName(), response.getToolCallId(),
                isError ? "error" : response.getStatus(), response.getMetadata());
    }

    private boolean isFailure(ToolCallResponse response, String rawResult) {
        if (response == null || response.isError()) return true;
        if ("error".equalsIgnoreCase(response.getStatus())) return true;
        if (rawResult == null) return false;
        Matcher m = NON_ZERO_EXIT.matcher(rawResult);
        if (m.find()) {
            try { return Integer.parseInt(m.group(1)) != 0; }
            catch (NumberFormatException ignored) { return true; }
        }
        return rawResult.matches("(?is).*\\b(?:command not found|no such file or directory|permission denied|command timed out)\\b.*");
    }

    private String sanitize(String text) {
        if (text == null || text.isBlank()) return text;
        Matcher matcher = SECRET_PATTERN.matcher(text);
        StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            String replacement;
            if (matcher.group(1) != null) replacement = matcher.group(1) + "[REDACTED]";
            else if (matcher.group(3) != null) replacement = matcher.group(3) + "[REDACTED_PRIVATE_KEY]" + matcher.group(5);
            else replacement = "[REDACTED_ACCESS_KEY]";
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private String resolveRunId(ToolCallRequest request) {
        return request.getExecutionContext().flatMap(ctx -> ctx.threadId()).orElseGet(() -> {
            Object context = request.getContext().get(SessionConfigKeys.AGENT_CONTEXT);
            return context instanceof AgentContext agentContext ? agentContext.getRunId() : null;
        });
    }

    private String abbreviate(String text) {
        if (text == null) return null;
        String oneLine = text.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= MAX_RESULT_LENGTH
                ? oneLine : oneLine.substring(0, MAX_RESULT_LENGTH) + "...truncated";
    }
}
