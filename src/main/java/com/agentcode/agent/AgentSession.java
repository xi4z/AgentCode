package com.agentcode.agent;

import com.agentcode.context.AgentContext;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.extension.tools.filesystem.FileSystemTools;
import com.alibaba.cloud.ai.graph.agent.hook.shelltool.ShellToolAgentHook;
import com.alibaba.cloud.ai.graph.agent.tools.GlobSearchTool;
import com.alibaba.cloud.ai.graph.agent.tools.GrepSearchTool;
import com.alibaba.cloud.ai.graph.agent.tools.ShellTool2;
import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.alibaba.cloud.ai.graph.streaming.OutputType;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.util.List;

public class AgentSession {
    public AgentSession(AgentContext agentContext, ChatModel chatModel, BaseCheckpointSaver saver) {
        this.agentContext = agentContext;
        this.reactAgent = ReactAgent.builder()
                .name("minimal_agent")
                .model(chatModel)
                .saver(saver)
                .tools(List.of(
                        GrepSearchTool.builder(agentContext.getWorkspace()).build(),
                        GlobSearchTool.builder(agentContext.getWorkspace()).build())
                )
                .methodTools(FileSystemTools.builder()
                        .rootDir(agentContext.getWorkspace()).maxFileSizeMb(10).build())
                .hooks(ShellToolAgentHook.builder()
                        .shellTool2(
                                ShellTool2.builder(agentContext.getWorkspace()).build()
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
    Status status; // 会话状态

    final AgentContext agentContext;
    final ReactAgent reactAgent;
    final RunnableConfig config;
    Disposable runningTask;

    public Flux<AgentStream> run(String goal) throws GraphRunnerException {
        if (!(status == Status.FREE)) {
            // TODO 此时需要进入队列中等待
        }
        status = Status.RUNNING;


        return Flux.create(sink -> {
            Disposable disposable = null;
            try {
                disposable = reactAgent.stream(goal, config).concatMap(this::classifyMessage)
                        .doOnNext(sink::next)
                        .doOnComplete(sink::complete)
                        .doOnError(sink::error)
                        .doFinally(
                                signalType -> {
                                    runningTask = null;
                                    status = Status.FREE;
                                }
                        )
                        .subscribe();

            } catch (GraphRunnerException e) {
                sink.error(e);
            }
            if (disposable != null) { // 如果没能成功执行就不推送
                runningTask = disposable;
                // 调用方取消/断开时，自动停止内部 Agent
                sink.onCancel(disposable::dispose);
                sink.onDispose(disposable::dispose);
            }
            // TODO 需要增加 Task 移除
            // TODO stop() 与返回的 Flux 生命周期不一致 stop() 只取消内部订阅，没有让外部返回的 Flux 正常结束。调用方可能一直挂住，收不到 complete/error/cancel。
        });
    }

    public void stop() {
        if (status != Status.RUNNING) {
            // TODO 此时抛出错误
        }
        if (runningTask != null) {
            runningTask.dispose();
        }
    }

    public void interrupt(String message) {
        if (status != Status.RUNNING || runningTask == null) {
            return; // TODO 应增加 Exception
        }
        reactAgent.interrupt(message, config);
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
