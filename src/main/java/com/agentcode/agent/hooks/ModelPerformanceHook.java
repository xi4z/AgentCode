package com.agentcode.agent.hooks;

import com.agentcode.agent.AgentTrace;
import com.agentcode.session.SessionEnum;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.hook.ModelHook;
import lombok.NoArgsConstructor;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

@NoArgsConstructor
public class ModelPerformanceHook extends ModelHook {
    // 在每次模型调用前后执行
    @Override
    public String getName() {
        return "";
    }

    @Override
    public CompletableFuture<Map<String, Object>> beforeModel(OverAllState state, RunnableConfig config) {
        // 记录时间
        config.context().put(SessionEnum.SINGLE_TIME.getCode(), System.currentTimeMillis());
        // 记录调用次数
        if (config.threadId().isPresent()){
            AgentTrace.modelCallStart(config.threadId().get());
        }
        return super.beforeModel(state, config);
    }

    @Override
    public CompletableFuture<Map<String, Object>> afterModel(OverAllState state, RunnableConfig config) {

        // 计数
        int count = config.context().containsKey(SessionEnum.TOTAL_COUNT.getCode()) ? (int) config.context().get(SessionEnum.TOTAL_COUNT.getCode()) : 0;
        config.context().put(SessionEnum.TOTAL_COUNT.getCode(), ++count);

        // 检查时间
        if (config.context().containsKey(SessionEnum.SINGLE_TIME.getCode()) && config.threadId().isPresent()) {

            // 修改单次计时
            long startTime = (long) config.context().get(SessionEnum.SINGLE_TIME.getCode());
            long duration = System.currentTimeMillis() - startTime;

            // 修改总计时
            long totalTime = config.context().containsKey(SessionEnum.TOTAL_DURATION.getCode())
                    ? (long) config.context().get(SessionEnum.TOTAL_COUNT.getCode()) : 0L;
            config.context().put(SessionEnum.TOTAL_DURATION.getCode(), duration + totalTime);

            // 输出日志
            AgentTrace.modelCallEnd(config.threadId().get(), count, duration);
        }


        return super.afterModel(state, config);
    }
}
