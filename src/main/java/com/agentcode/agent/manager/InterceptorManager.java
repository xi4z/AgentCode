package com.agentcode.agent.manager;

import com.agentcode.agent.interceptor.ToolPerformanceInterceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.Interceptor;

import java.util.ArrayList;
import java.util.List;

/**
 * ReactAgent 的拦截器装配。
 *
 * 与 ToolManager / HooksManager 同一套写法：Builder 逐个登记，build() 交出可直接
 * 传给 {@code ReactAgent.builder().interceptors(...)} 的列表。
 */
public class InterceptorManager {

    private InterceptorManager() {
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {

        private final List<Interceptor> interceptors = new ArrayList<>();

        /** 工具调用耗时与异常 */
        public Builder toolPerformance() {
            interceptors.add(new ToolPerformanceInterceptor());
            return this;
        }

        public List<Interceptor> build() {
            return List.copyOf(interceptors);
        }
    }
}
