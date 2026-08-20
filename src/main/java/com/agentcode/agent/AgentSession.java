package com.agentcode.agent;

import com.agentcode.common.ShellHelper;
import com.agentcode.context.AgentContext;
import com.agentcode.exception.AgentAlreadyRunningException;
import com.agentcode.exception.InterruptFailException;
import com.agentcode.exception.StopFailException;
import com.agentcode.exception.TaskNotFoundException;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.action.Command;
import com.alibaba.cloud.ai.graph.action.InterruptionMetadata;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.extension.tools.filesystem.FileSystemTools;
import com.alibaba.cloud.ai.graph.agent.hook.InterruptionHook;
import com.alibaba.cloud.ai.graph.agent.hook.shelltool.ShellToolAgentHook;
import com.alibaba.cloud.ai.graph.agent.tools.GlobSearchTool;
import com.alibaba.cloud.ai.graph.agent.tools.GrepSearchTool;
import com.alibaba.cloud.ai.graph.agent.tools.ShellTool;
import com.alibaba.cloud.ai.graph.agent.tools.ShellTool2;
import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.alibaba.cloud.ai.graph.streaming.OutputType;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.nio.file.Path;
import java.nio.file.Paths;
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
        if (!(nodeOutput instanceof StreamingOutput sop) && !(nodeOutput instanceof InterruptionMetadata)) {
            return Flux.empty();
        }
        if (nodeOutput instanceof InterruptionMetadata metadata) {
            preHandleAgentInterrupt(metadata)
            return Flux.empty();
        }
        OutputType type = sop.getOutputType();
        Message message = sop.message();
        AgentStream agentStream = null;
        // 如果当前是工具审批中断:




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

    /**
     * 用于预处理需要 HumanInLoop 环节的信息
     * shell write 工具 默认需要 interrupt.
     * 但是用户可能期望他们已经批准过的指令不要再次打扰他们, 于是可能会设置使用通配符来统一过滤已经过滤过的指令
     * 即有四个选项
     * 1. 同意(APPROVE)
     * 2. 在本会话中一律批准满足通配符的指令(APPROVE_ALL)
     * 3. 拒绝(REJECT)
     * 4. 修改意见(EDIT)
     * --
     * 1. 将复合指令拆成多个指令, 比如以 | 连接的, 再走模式匹配
     * 2. 检查是否命中黑名单, 比如 rm 等, 命中一律确认
     * 3. 检查是否突破工作目录, 突破一律确认
     * 4. 走缓存, 如果缓存没有确认
     * 5. 工具的默认策略, 当然工具也有缓存
     *
     * 应该使用 WebSocket 向用户发送 WebSocket 信息. 并等待接收
     * @param metadata
     * @return
     */
    private Flux<AgentStream> preHandleAgentInterrupt(InterruptionMetadata metadata) {
        // 先检查工具类型, 如果是 shell 先拆分指令然后走 shell 处理路线
        List<InterruptionMetadata.ToolFeedback> toolFeedbacks = metadata.toolFeedbacks();
        for (InterruptionMetadata.ToolFeedback feedback : toolFeedbacks) {
            // 当前只拦截 write_file, edit 与 shell
            if (!feedback.getName().equalsIgnoreCase("shell")){
                // 此时检查工作目录即可
                if (checkPathValid(feedback.getArguments()))
            }else {
                // 检查 shell, 需要对可能的多重指令进行拆分并尝试进行模式匹配

            }

        }


    }

    /**
     * 检查 Shell 参数
     * @param feedback
     * @return
     */
    private boolean checkShellValid(InterruptionMetadata.ToolFeedback feedback) {
        String command = "";
        try { // 先从 json 中解析出 command
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(feedback.getArguments());
            command = root.path("command").asText();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        List<String> commands = ShellHelper.splitCommand(command);

    }

    private boolean checkCommandValid(String command) {



    }
    private boolean checkPathValid(String path) {
        // TODO 传过来的参数应该是json化的,需要提取出目录
        ObjectMapper mapper = new ObjectMapper();
        String filePath = "";
        try {
            JsonNode root = mapper.readTree(path);
            filePath = root.path("filepath").asText();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        if (filePath.isEmpty()){
            return false;
        }
        Path basePath = Paths.get(agentContext.getWorkspace()).toAbsolutePath().normalize();
        Path resolvedPath = basePath.resolve(filePath);
        Path normalizedPath = resolvedPath.normalize();
        return normalizedPath.startsWith(basePath);
    }

    private void buildApprovalRequest(String description){
        // 组装请求并传送至 WebSocket

    }
}
