package com.agentcode.session;

import com.agentcode.context.AgentContext;
import com.agentcode.dto.AgentApprovalManager;
import com.agentcode.dto.AgentInterruptHandle;
import com.agentcode.dto.AgentStream;
import com.agentcode.exception.AgentAlreadyRunningException;
import com.agentcode.exception.InterruptFailException;
import com.agentcode.exception.StopFailException;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.action.InterruptionMetadata;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.tools.ShellTool2;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.alibaba.cloud.ai.graph.streaming.OutputType;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.agentcode.common.ShellParseHelper.extractShellCommand;

/**
 * AgentSession 运行时的状态
 */
public class AgentSession {


    /**
     * 运行槽：null 表示当前没有 run。
     * "有没有在跑"和"跑的是谁"合并成同一个原子引用，所以不需要额外加锁，
     * 也不会出现 status 与 runningTask 各说各话的情况。
     */
    private final AtomicReference<Run> current = new AtomicReference<>();

    /** 一次 run 的句柄：事件出口 + 图的上游订阅 */
    private static final class Run {
        private final Sinks.Many<AgentStream> sink;
        private volatile Disposable upstream;

        private Run(Sinks.Many<AgentStream> sink) {
            this.sink = sink;
        }
    }

    private final AgentContext agentContext;
    private final ReactAgent reactAgent;
    private final ShellTool2 shellTool2;
    private final AgentApprovalManager approvalManager;

    private volatile RunnableConfig config;

    public AgentSession(AgentContext agentContext, AgentSessionRuntime runtime) {
        this.agentContext = agentContext;
        this.reactAgent = runtime.getReactAgent();
        this.shellTool2 = runtime.getShellTool2();
        this.approvalManager = runtime.getApprovalManager();
        this.config = runtime.getInitialConfig();
    }

    /** 会话运行态：从运行槽与待审批上下文推导，不单独维护 */
    public SessionStatus status() {
        if (current.get() != null) {
            return SessionStatus.RUNNING;
        }
        return config.context().containsKey(SessionEnum.HANDLED_INTERRUPTED.getCode())
                ? SessionStatus.INTERRUPTED
                : SessionStatus.FREE;
    }

    public Flux<AgentStream> run(String goal){
        // 新的非空输入表示开启新的一轮对话，不应继续携带上一次审批恢复的 feedback 元数据
        if (goal != null && !goal.isBlank()) {
            config.context().remove(RunnableConfig.HUMAN_FEEDBACK_METADATA_KEY);
            config.metadata().ifPresent(metadata -> metadata.remove(RunnableConfig.HUMAN_FEEDBACK_METADATA_KEY));
        }

        return run(goal, config);
    }

    private Flux<AgentStream> run(String goal, RunnableConfig runConfig){
        Sinks.Many<AgentStream> sink = Sinks.many()
                .unicast()
                .onBackpressureBuffer();
        Run run = new Run(sink);
        // 占位成功才真正开跑：同一个会话同一时刻只允许一个 run，重复 run 直接被拒
        if (!current.compareAndSet(null, run)) {
            throw new AgentAlreadyRunningException("会话:" + agentContext.getRunId() + "正在运行");
        }

        try {
            // 2. 启动内部 Agent，把事件转发到 sink
            run.upstream = reactAgent.stream(goal, runConfig).concatMap(this::classifyMessage)
                    .doOnNext(sink::tryEmitNext)
                    .doOnComplete(() -> {
                        // 只释放自己占的槽：本轮若已把槽让给恢复流，这里不能误清
                        current.compareAndSet(run, null);
                        sink.tryEmitComplete();
                    })
                    .doOnError(error -> {
                        current.compareAndSet(run, null);
                        sink.tryEmitError(error);
                    })
                    .subscribe();
        } catch (GraphRunnerException e) {
            current.compareAndSet(run, null);
            sink.tryEmitError(e);
            return sink.asFlux();
        }

        // 3. sink 由本轮持有；终止信号由 doOnComplete/doOnError 或 stop() 负责发出。
        //    下游取消（如客户端断开）不释放运行槽，避免旧图还在跑时放进第二个 run。
        return sink.asFlux();
    }

    public void stop() {
        // 原子摘走运行槽：摘不到就是没在跑；摘到了本次 stop 就是唯一的"停"操作，
        // 不会与并发的 run()/doOnComplete() 抢同一份状态
        Run run = current.getAndSet(null);
        if (run == null) {
            throw new StopFailException("会话: " + agentContext.getRunId() + "停止失败, 因为当前会话没有在进行中");
        }
        Disposable upstream = run.upstream;
        if (upstream != null) {
            upstream.dispose();
        }
        run.sink.tryEmitComplete();
    }

    // 插话先不做
    public void interrupt(String message) {
        if (current.get() == null) {
            throw new InterruptFailException("会话: " + this.agentContext.getRunId() + "打断失败, 因为当前会话没有在进行中");
        }
        reactAgent.interrupt(message, config);
    }
    private Flux<AgentStream> classifyMessage(NodeOutput nodeOutput) {
        if (nodeOutput instanceof InterruptionMetadata metadata) {
            return preHandleToolApproval(metadata);
        }
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

    /**
     * 输入已经完成的审批
     * @param handles
     * @return
     */
    public Flux<AgentStream> handleToolApproval(AgentInterruptHandle[] handles) {
        // 待审批上下文本身就是 INTERRUPTED 的判定依据，不再单独维护状态
        Object raw = config.context().get(SessionEnum.HANDLED_INTERRUPTED.getCode());
        if (!(raw instanceof InterruptionMetadata.Builder handledInterruption)) {
            throw new InterruptFailException("会话: " + this.agentContext.getRunId() + "恢复中断失败, 因为当前会话没有待处理的审批");
        }

        Map<String, InterruptionMetadata.ToolFeedback> pendingInterrupted = (Map<String, InterruptionMetadata.ToolFeedback>) config.context().get(SessionEnum.PENDING_INTERRUPTED.getCode());

        // 对传过来的每个 interrupt 做处理
        for (AgentInterruptHandle handle : handles) {
            // 拿到对应的处理
            InterruptionMetadata.ToolFeedback original = pendingInterrupted.get(handle.getId());
            if (original == null) {
                continue; // 如果待审批的工具中没有发来的, 就直接跳过
            }
            String originalArguments = original.getArguments();

            InterruptionMetadata.ToolFeedback.Builder fbBuilder = InterruptionMetadata.ToolFeedback.builder()
                    .name(handle.getName())
                    .id(handle.getId())
                    .description(handle.getDescription() != null ? handle.getDescription() : original.getDescription())
                    .arguments(approvalManager.resolveArguments(handle, originalArguments));

            switch (handle.getDecision()) {
                case APPROVED -> fbBuilder.result(InterruptionMetadata.ToolFeedback.FeedbackResult.APPROVED);
                case APPROVE_ALL -> {
                    fbBuilder.result(InterruptionMetadata.ToolFeedback.FeedbackResult.APPROVED);
                    approvalManager.rememberApproval(handle, originalArguments);
                }
                case EDITED -> fbBuilder.result(InterruptionMetadata.ToolFeedback.FeedbackResult.EDITED);
                default -> fbBuilder.result(InterruptionMetadata.ToolFeedback.FeedbackResult.REJECTED);
            }

            handledInterruption.addToolFeedback(fbBuilder.build());
            // 没什么问题就移除 pending
            pendingInterrupted.remove(handle.getId());
        }

        // 如果没有移除干净, 就打回重新写
        if (!pendingInterrupted.isEmpty()) {
            return Flux.just(new AgentStream(
                    AgentStream.Status.PERMISSION_REQUESTED,
                    approvalManager.toPermissionJson(pendingInterrupted.values().stream().toList())
            ));
        }

        InterruptionMetadata data = handledInterruption.build();
        RunnableConfig newConfig = RunnableConfig.builder()
                .threadId(agentContext.getRunId())
                .addMetadata(RunnableConfig.HUMAN_FEEDBACK_METADATA_KEY, data)
                .build();
        config.context().remove(SessionEnum.HANDLED_INTERRUPTED.getCode());
        config.context().remove(SessionEnum.PENDING_INTERRUPTED.getCode());
        config = newConfig;
        config.context().put(SessionEnum.AGENT_CONTEXT.getCode(), agentContext);
        // 第一次流中断后 ShellToolAgentHook 会清理会话，恢复前需要重新初始化 shell session
        shellTool2.getSessionManager().initialize(newConfig);
        return run("");
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
     * 应该使用 WebSocket 向用户发送 WebSocket 信息. 并等待接收
     */
    private Flux<AgentStream> preHandleToolApproval(InterruptionMetadata metadata) {
        // 先检查工具类型, 如果是 shell 先拆分指令然后走 shell 处理路线
        List<InterruptionMetadata.ToolFeedback> toolFeedbacks = metadata.toolFeedbacks();
        // 已经被处理的 interruption
        InterruptionMetadata.Builder handledInterruption = InterruptionMetadata.builder()
                .nodeId(metadata.node())
                .state(metadata.state());


        List<InterruptionMetadata.ToolFeedback> waitForHandles = new ArrayList<>();
        for (InterruptionMetadata.ToolFeedback feedback : toolFeedbacks) {
            // 当前只拦截 write_file, edit 与 shell
            InterruptionMetadata.ToolFeedback.Builder currFeedback =
                    InterruptionMetadata.ToolFeedback.builder(feedback); // 先预处理
            if (!feedback.getName().equalsIgnoreCase("shell")){
                // 此时检查工作目录即可
                if (approvalManager.checkPathValid(feedback.getArguments())) {
                    // TODO 路径合法时按后续审批策略决定自动放行或继续询问
                    currFeedback.result(InterruptionMetadata.ToolFeedback.FeedbackResult.APPROVED);
                }
            } else {
                // 检查 shell, 需要对可能的多重指令进行拆分并尝试进行模式匹配
                String command = extractShellCommand(feedback.getArguments());
                // 静态评估通过，或当前会话已经放行过这条命令/这类命令，则无需再人工审批
                if (approvalManager.checkCommandValid(command) || approvalManager.isSessionApproved(command)) {
                    // TODO 自动放行/恢复执行
                    currFeedback.result(InterruptionMetadata.ToolFeedback.FeedbackResult.APPROVED);
                }
//                else {
//                    // TODO 发送 WebSocket 审批请求；用户选择 APPROVE_ALL 时调用
//                    //      approveCommandForSession(command) / approvePatternForSession(pattern)
//                }
            }
            InterruptionMetadata.ToolFeedback fb = currFeedback.build();
            if (fb.getResult() == null){
                waitForHandles.add(fb);
            }else {
                handledInterruption.addToolFeedback(fb); // 否则就增加到已就绪的 fb 中
            }
        }
        config.context().put(SessionEnum.HANDLED_INTERRUPTED.getCode(), handledInterruption);
        // 拿到需要处理审批的请求原始数据, 并以 id 做键区分
        config.context().put(SessionEnum.PENDING_INTERRUPTED.getCode(), waitForHandles.stream().collect(Collectors.toMap(InterruptionMetadata.ToolFeedback::getId, Function.identity())));

        // 有需要人工审批的工具时，发送 permission.requested 给前端并中断当前流
        if (!waitForHandles.isEmpty()) {
            return Flux.just(new AgentStream(
                    AgentStream.Status.PERMISSION_REQUESTED,
                    approvalManager.toPermissionJson(waitForHandles)
            ));
        }

        // 全部自动放行（安全命令/会话缓存命中）：不打扰用户，直接恢复执行。
        // 本轮图已经停在中断点上、不会再往前走，所以先把运行槽让给恢复流，它才是接下来真正在跑的那个；
        // 延迟 1ms 是为了避免在 concatMap 处理中重入 run()
        Run holder = current.get();
        return Flux.defer(() ->
                Mono.delay(java.time.Duration.ofMillis(1))
                        .flatMapMany(ignore -> {
                            if (holder != null) {
                                current.compareAndSet(holder, null);
                            }
                            return handleToolApproval(new AgentInterruptHandle[0]);
                        })
        );
    }
}
