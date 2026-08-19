package com.agentcode.agent;

import com.agentcode.context.AgentContext;
import com.agentcode.exception.AgentAlreadyRunningException;
import com.agentcode.exception.InterruptFailException;
import com.agentcode.exception.StopFailException;
import com.agentcode.exception.TaskNotFoundException;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.extension.tools.filesystem.FileSystemTools;
import com.alibaba.cloud.ai.graph.agent.hook.shelltool.ShellToolAgentHook;
import com.alibaba.cloud.ai.graph.agent.tools.GlobSearchTool;
import com.alibaba.cloud.ai.graph.agent.tools.GrepSearchTool;
import com.alibaba.cloud.ai.graph.agent.tools.ShellTool2;
import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.alibaba.cloud.ai.graph.streaming.OutputType;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import lombok.AllArgsConstructor;
import lombok.Data;
import okio.Sink;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.List;

public class AgentSession {
    public AgentSession(AgentContext agentContext, ChatModel chatModel, BaseCheckpointSaver saver) {
        this.agentContext = agentContext;
        String workspace = resolveWorkspace(agentContext);
        this.reactAgent = ReactAgent.builder()
                .name("minimal_agent")
                .model(chatModel)
                .saver(saver)
                .tools(List.of(
                        GrepSearchTool.builder(workspace).build(),
                        GlobSearchTool.builder(workspace).build())
                )
                .methodTools(FileSystemTools.builder()
                        .rootDir(workspace).maxFileSizeMb(10).build())
                .hooks(ShellToolAgentHook.builder()
                        .shellTool2(
                                ShellTool2.builder(workspace).build()
                        )
                        .shellToolName("shell")
                        .build())
                .build();

        // 在重新 run 之后, 修改 context 状态
        this.config = RunnableConfig.builder()
                .threadId(agentContext.getRunId()) // 获取数据
                .build();
    }

    public enum Status{
        FREE, // 当前会话没有在运行
        RUNNING, // 当前会话正在运行
        INTERRUPTED // 当前会话被中断, 出现这种状态的原因通常是 Agent 正在等待用户审批
    }
    private volatile Status status = Status.FREE; // 会话状态

    final AgentContext agentContext;
    final ReactAgent reactAgent;
    final RunnableConfig config;

    @AllArgsConstructor
    @Data
    private static class RunningTask{
        Disposable disposable;
        Sinks.Many<AgentStream> Sink;
    }
    private volatile RunningTask runningTask;

    public Flux<AgentStream> run(String goal){
        Sinks.Many<AgentStream> sink;
        synchronized (this) {
            if (status != Status.FREE) {
                // 此时不允许run
                throw new AgentAlreadyRunningException("会话:" + agentContext.getRunId() + "正在运行");
            }
            status = Status.RUNNING;
            sink = Sinks.many()
                    .unicast()
                    .onBackpressureBuffer();
        }

        Disposable disposable;
        try {
            // 2. 启动内部 Agent，把事件转发到 sink
            disposable = reactAgent.stream(goal, config).concatMap(this::classifyMessage)
                    .doOnNext(agentStream -> sink.tryEmitNext(agentStream))
                    .doOnComplete(() -> {
                        sink.tryEmitComplete();
                        synchronized (this) {
                            runningTask = null;
                            status = Status.FREE;
                        }
                    })
                    .doOnError(error -> {
                        sink.tryEmitError(error);
                        synchronized (this) {
                            runningTask = null;
                            status = Status.FREE;
                        }
                    })
                    .subscribe();
        } catch (GraphRunnerException e) {
            sink.tryEmitError(e);
            synchronized (this) {
                runningTask = null;
                status = Status.FREE;
            }
            return sink.asFlux();
        }

        // 3. 保存 disposable + sink，供 stop() 使用
        RunningTask task = new RunningTask(disposable, sink);
        synchronized (this) {
            // 如果流已经同步结束，doOnComplete/doOnError 已把 runningTask 置空，不能再放回已完成任务
            if (status == Status.RUNNING) {
                runningTask = task;
            }
        }
        return sink.asFlux()
                .doFinally(signal -> {
                    synchronized (this) {
                        // 只清理当前这次 run 的任务，避免旧流结束时误清新会话的任务
                        if (runningTask == task) {
                            runningTask = null;
                        }
                    }
                });
    }

    public void stop() {
        RunningTask task;
        synchronized (this) {
            if (status == Status.FREE) {
                throw new StopFailException("会话: " + this.agentContext.getRunId() + "停止失败, 因为当前会话没有在进行中");
            }
            task = runningTask;
            if (task == null) {
                throw new TaskNotFoundException("当前没有执行任务: " + agentContext.getRunId());
            }

            // 在锁内先摘引用并置为 FREE，防止重复 stop 或并发 run
            runningTask = null;
            status = Status.FREE;
        }

        // 锁外执行真正的中断/完成通知，避免持锁做耗时或阻塞操作
        task.getDisposable().dispose();
        task.getSink().tryEmitComplete();
    }

    public void interrupt(String message) {
        synchronized (this) {
            if (status != Status.RUNNING || runningTask == null) {
                throw new InterruptFailException("会话: " + this.agentContext.getRunId() + "打断失败, 因为当前会话没有在进行中");
            }
        }
        reactAgent.interrupt(message, config);
    }

    private String resolveWorkspace(AgentContext agentContext) {
        String workspace = agentContext.getWorkspace();
        if (workspace == null || workspace.isBlank()) {
            return System.getProperty("user.dir");
        }
        return workspace;
    }


    private Flux<AgentStream> classifyMessage(NodeOutput nodeOutput) {
        if (!(nodeOutput instanceof StreamingOutput sop)) {
            return Flux.empty();
        }

        OutputType type = sop.getOutputType();
        Message message = sop.message();
        AgentStream agentStream = null;
        // 处理流式输出

        if (message instanceof AssistantMessage assistantMessage){
            Object thinking = assistantMessage.getMetadata().get("reasoningContent");
            boolean isThinking = thinking != null && !thinking.toString().isEmpty();
            boolean isTool = assistantMessage.hasToolCalls();
            // 先检查是否是 Thinking
            if (type == OutputType.AGENT_MODEL_STREAMING){
                if (isThinking){ // 确定是思考消息, 封装
                    agentStream = new AgentStream(
                            AgentStream.Status.THINKING_STREAMING,
                            thinking.toString()
                    );
                } else { // 否则可能是普通回答
                    agentStream = new AgentStream(
                            AgentStream.Status.RESPONSE_STREAMING,
                            message.getText()
                    );
                }
            }
            // 处理结束输出
            else if (type == OutputType.AGENT_MODEL_FINISHED){
                // 先检查是不是工具调用
                if (isTool){
                    StringBuilder toolContent = new StringBuilder();
                    for (AssistantMessage.ToolCall tool :assistantMessage.getToolCalls()){
                        toolContent.append(tool.name()).append("|");
                    }
                    agentStream = new AgentStream(
                            AgentStream.Status.TOOL_STREAMING,
                            toolContent.toString()
                    );
                } else if (isThinking) {
                    agentStream = new AgentStream(
                            AgentStream.Status.THINKING_FINISHED,
                            thinking.toString()
                    );
                }else {
                    agentStream = new AgentStream(
                            AgentStream.Status.RESPONSE_FINISHED,
                            message.getText()
                    );
                }
            }
        } else if (message instanceof ToolResponseMessage trm) {
            agentStream = new AgentStream(
                    AgentStream.Status.TOOL_FINISHED,
                    "[TOOL_FINISHED]"
            );
        }
        if (agentStream != null) {
            return Flux.just(agentStream);
        }else  {
            return Flux.empty();
        }
    }
}
