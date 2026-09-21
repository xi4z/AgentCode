package com.agentcode.agent.interceptor;

import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallResponse;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolInterceptor;
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
        long startTime = System.currentTimeMillis();

        log.info("执行工具: {}", toolName);

        try {
            ToolCallResponse response = handler.call(request);

            long duration = System.currentTimeMillis() - startTime;
            log.info("工具 {} 执行成功 (耗时: {}ms)", toolName, duration);

            return response;
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            log.info("工具 {} 执行失败 (耗时: {}ms): {}", toolName, duration, e.getMessage());

            return ToolCallResponse.of(
                    request.getToolCallId(),
                    request.getToolName(),
                    "工具执行失败: " + e.getMessage()
            );
        }
    }
}
