package com.agentcode.agent.interceptor;

import com.agentcode.agent.AgentTrace;
import com.alibaba.cloud.ai.graph.agent.interceptor.*;
import lombok.extern.slf4j.Slf4j;

// 工具调用性能监控
@Slf4j
public class ToolPerformanceInterceptor extends ToolInterceptor {

    @Override
    public String getName() {
        return "ToolPerformanceInterceptor";
    }

    @Override
    public ToolCallResponse interceptToolCall(ToolCallRequest request, ToolCallHandler handler) {
        String toolName = request.getToolName();
        String toolArgs = request.getArguments();
        long startTime = System.currentTimeMillis();

        String runId = request.getExecutionContext()
                .flatMap(ToolCallExecutionContext::threadId)
                .orElse(null);
        AgentTrace.toolExecution(runId, toolName, toolArgs);
        try {
            ToolCallResponse response = handler.call(request);
            String res = response.getResult();
            long duration = System.currentTimeMillis() - startTime;
            AgentTrace.toolSuccess(runId, toolName, toolArgs, res, String.valueOf(duration));

            return response;
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            AgentTrace.toolFailure(runId, toolName, toolArgs, e.getMessage(), String.valueOf(duration));
            return ToolCallResponse.of(
                    request.getToolCallId(),
                    request.getToolName(),
                    "工具执行失败: " + e.getMessage()
            );
        }
    }
}
