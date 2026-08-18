package com.agentcode.service.Impl;

import com.agentcode.agent.AgentHandleInterrupt;
import com.agentcode.agent.AgentLoop;
import com.agentcode.agent.AgentStream;
import com.agentcode.context.AgentContext;
import com.agentcode.exception.AgentContextNotFoundException;
import com.agentcode.exception.InvalidStatusException;
import com.agentcode.exception.TaskNotFoundException;
import com.agentcode.service.ReactAgentService;
import com.agentcode.store.InMemoryAgentContextStore;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
public class ReActAgentServiceImpl implements ReactAgentService {

    private final AgentLoop agentLoop;
    private final InMemoryAgentContextStore agentContextStore;
    private final ConcurrentHashMap<String, Disposable> runningTasks = new ConcurrentHashMap<>();

    public Flux<AgentStream> run(String goal, String runId) {
        AgentContext agentContext;
        try{
            agentContext = this.getAgentContext(runId);
        } catch(AgentContextNotFoundException ex){
            agentContext = AgentContext.builder().runId(runId).goal(goal).build();
        }


        // Spring Ai Alibaba 最佳实践: 可以随时暂停且不引入更多的变量
        AgentContext finalAgentContext = agentContext;
        return Flux.create(sink -> {
            Disposable disposable = null;
            try {
                disposable = agentLoop.run(finalAgentContext)
                        .doOnNext(sink::next)
                        .doOnComplete(sink::complete)
                        .doOnError(sink::error)
                        .subscribe();

            } catch (GraphRunnerException e) {
                sink.error(e);
            }
            if (disposable != null) { // 如果没能成功执行就不推送
                runningTasks.put(runId, disposable);
            }
            // 调用方取消/断开时，自动停止内部 Agent
            sink.onCancel(disposable::dispose);
            sink.onDispose(disposable::dispose);
            // TODO 需要增加 Task 移除
        });
    }

    @Override
    public void stop(String runId) {
        // 先检查能否拿到上下文且不抛出错误
        getAgentContext(runId);

        // 再检查任务是否存在
        if (runningTasks.containsKey(runId)) {
            runningTasks.get(runId).dispose();
            runningTasks.remove(runId);
        }else {
            throw new TaskNotFoundException("当前没有执行任务: " + runId);
        }
    }

    @Override
    public void handleInterrupt(AgentHandleInterrupt handle) {
        // TODO 需要根据传参来确定如何进行
    }

    @Override
    public void interrupt(String runId, String guidanceMessage) {
        // 先确定这个 runId 是否存在
        AgentContext agentContext = getAgentContext(runId);
        // 不在运行中时则抛出错误
        if (!agentContext.getStatus().equals(AgentContext.Status.RUNNING)) {
            throw new InvalidStatusException("当前会话的状态为: " + agentContext.getStatus().toString() + " , 必须是 RUNNING 时才可打断");
        } else if (!runningTasks.containsKey(runId)) {
            throw new TaskNotFoundException("当前没有执行任务: " + runId);
        }

        agentLoop.getReactAgentMap().get(runId).interrupt(guidanceMessage, RunnableConfig.builder()
                        .threadId(runId).build());
    }

    private AgentContext getAgentContext(String runId) {
        if (agentContextStore.find(runId).isEmpty()) {
            throw new AgentContextNotFoundException("该会话不存在: " + runId);
        }
        return agentContextStore.find(runId).get();
    }
}
