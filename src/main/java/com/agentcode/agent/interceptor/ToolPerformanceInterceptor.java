package com.agentcode.agent.interceptor;

import com.agentcode.agent.AgentTrace;
import com.alibaba.cloud.ai.graph.agent.interceptor.*;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

// 工具调用性能监控：日志交给 AgentTrace，这里只管计时与放行
@Slf4j
@NoArgsConstructor
public class ToolPerformanceInterceptor extends ToolInterceptor {

    @Override
    public String getName() {
        return "ToolPerformanceInterceptor";
    }

    @Override
    public ToolCallResponse interceptToolCall(ToolCallRequest request, ToolCallHandler handler) {
        String runId = request.getExecutionContext()
                .flatMap(ToolCallExecutionContext::threadId)
                .orElse(null);
        String toolName = request.getToolName();
        long startTime = System.currentTimeMillis();
        AgentTrace.toolExecution(runId, toolName, request.getArguments());
        try {
            ToolCallResponse response = handler.call(request);
            AgentTrace.toolSuccess(runId, toolName, System.currentTimeMillis() - startTime);
            return response;
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            // 这里要把失败原样告诉模型（沿用原行为），审计只留一行
            AgentTrace.toolFailure(runId, toolName, e.getMessage(), duration);
            return ToolCallResponse.of(
                    request.getToolCallId(),
                    request.getToolName(),
                    "工具执行失败: " + e.getMessage()
            );
        }
    }
}
