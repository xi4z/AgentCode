package com.agentcode.session;

import com.agentcode.agent.AgentTrace;
import com.agentcode.agent.context.AgentContext;
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
import com.alibaba.cloud.ai.dashscope.spec.DashScopeApiSpec;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.openai.api.OpenAiApi;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
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

    /** 最近一次非空的模型用量；每次模型调用收口后清空 */
    private Usage lastModelUsage;

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
        // 开工时刻要在"占住运行槽之后"才写：写在拿到槽之前的话，一个被拒的并发请求
        // 会把这轮的计时起点推到更早（实测出现 wallClock 136s / 真实轮次 10s 的假数据）。
        // 审批恢复走的是 run("")，goal 为空时保留原起点，中断挂起的时间仍算进这一轮。
        if (goal != null && !goal.isBlank()) {
            agentContext.setAgentStartMs(System.currentTimeMillis());
        }

        // 本轮是否已经收过口（afterAgent 的 Run E、或中断点的 Run I）；兜底收口只做一次
        AtomicBoolean completed = new AtomicBoolean(false);

        try {
            // 2. 启动内部 Agent，把事件转发到 sink
            run.upstream = reactAgent.stream(goal, runConfig).concatMap(this::classifyMessage)
                    .doOnNext(sink::tryEmitNext)
                    .doOnComplete(() -> {
                        completed.set(true);
                        // 只释放自己占的槽：本轮若已把槽让给恢复流，这里不能误清
                        current.compareAndSet(run, null);
                        sink.tryEmitComplete();
                    })
                    .doOnError(error -> {
                        auditRunInterrupted(completed.get());
                        // 只释放自己占的槽：本轮若已把槽让给恢复流，这里不能误清
                        current.compareAndSet(run, null);
                        sink.tryEmitError(error);
                    })
                    .subscribe();
        } catch (GraphRunnerException e) {
            auditRunInterrupted(completed.get());
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
        // dispose 之后 doOnComplete 不会再触发，轮次收口只能在这里补
        auditRunInterrupted(false);
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
        // 模型调用用量随图输出带出来，在这里按"每次调用收口"记账
        recordModelUsage(type, sop.tokenUsage());
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
     * 模型调用用量统计。
     *
     * <p>用量不在请求上（发出前还没有），也不在拦截器的 ModelResponse 上可靠可取（流式下
     * getChatResponse() 为空），而是随图输出带出来：每个 chunk 的 ChatResponse 用量会被挂到
     * NodeOutput.tokenUsage()。一次模型调用对应一条 AGENT_MODEL_FINISHED，中间块可能是
     * null / EmptyUsage，所以缓存最近一次非空值兜底。
     */
    private void recordModelUsage(OutputType type, Usage usage) {
        if (usage != null && !(usage instanceof EmptyUsage)) {
            this.lastModelUsage = usage;
        }
        if (type != OutputType.AGENT_MODEL_FINISHED || lastModelUsage == null) {
            return;
        }
        Usage usageOfThisCall = this.lastModelUsage;
        this.lastModelUsage = null;
        // 累计量跟其它账目一样落在 config.context() 上，AgentTrace 只负责打日志
        long totalUsage = usageTotal() + tokenOf(usageOfThisCall.getPromptTokens())
                + tokenOf(usageOfThisCall.getCompletionTokens());
        config.context().put(SessionEnum.TOTAL_USAGE.getCode(), totalUsage);
        AgentTrace.modelUsage(agentContext.getRunId(), usageOfThisCall,
                AgentTrace.cachedTokens(usageOfThisCall), totalUsage);
    }

    private long usageTotal() {
        Object value = config.context().get(SessionEnum.TOTAL_USAGE.getCode());
        return value instanceof Number number ? number.longValue() : 0L;
    }

    /**
     * 轮次收口的兜底：图正常走完时 {@code afterAgent} 已经出过 Run E，这里什么都不做；
     * 异常终止、或停在审批中断点上（HITL 不执行 afterAgent）时补一条 Run I。
     */
    private void auditRunInterrupted(boolean completed) {
        if (completed) {
            return;
        }
        long start = agentContext.getAgentStartMs();
        AgentTrace.runEvent(agentContext.getRunId(), 'I',
                config.context().get(SessionEnum.TOTAL_COUNT.getCode()) instanceof Number calls
                        ? calls.longValue() : 0L,
                start == 0L ? 0L : System.currentTimeMillis() - start,
                usageTotal());
    }

    private static long tokenOf(Integer tokens) {
        return tokens == null ? 0L : tokens;
    }



    /**
     * 把 {@code config.context()} 上的累计量取出来，用于种进重建后的 config。
     * 只搬"总量"三个键，不含单次计时与审批态。
     */
    private Map<String, Object> carryTotals() {
        Map<String, Object> carried = new HashMap<>();
        for (SessionEnum key : List.of(SessionEnum.TOTAL_COUNT, SessionEnum.TOTAL_DURATION, SessionEnum.TOTAL_USAGE)) {
            Object value = config.context().get(key.getCode());
            if (value != null) {
                carried.put(key.getCode(), value);
            }
        }
        return carried;
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
                // 待审批列表里没有这个 id（重复答复/伪造 id）：跳过，但审计留痕
                AgentTrace.approval(agentContext.getRunId(), 'A', handle.getName(), handle.getId(),
                        "decision: " + handle.getDecision() + " ; skipped: not-pending");
                continue; // 如果待审批的工具中没有发来的, 就直接跳过
            }
            String originalArguments = original.getArguments();
            String resolvedArguments = approvalManager.resolveArguments(handle, originalArguments);

            InterruptionMetadata.ToolFeedback.Builder fbBuilder = InterruptionMetadata.ToolFeedback.builder()
                    .name(handle.getName())
                    .id(handle.getId())
                    .description(handle.getDescription() != null ? handle.getDescription() : original.getDescription())
                    .arguments(resolvedArguments);

            // 审计文案单独算：switch 只管落 FeedbackResult，文案里能多带一句后果
            String audit = switch (handle.getDecision()) {
                // 记生效的 args，不是原始 args：APPROVE_ALL 也允许前端带改后的参数
                case APPROVE_ALL ->
                        "decision: APPROVE_ALL ; session-cache: " + approvalManager.rememberApproval(handle, originalArguments)
                                + " ; args: " + resolvedArguments;
                case APPROVED -> "decision: APPROVED ; args: " + resolvedArguments;
                case EDITED -> "decision: EDITED ; args: " + resolvedArguments;
                default -> "decision: REJECTED ; result-to-model: rejected-by-user";
            };

            switch (handle.getDecision()) {
                case APPROVED, APPROVE_ALL -> fbBuilder.result(InterruptionMetadata.ToolFeedback.FeedbackResult.APPROVED);
                case EDITED -> fbBuilder.result(InterruptionMetadata.ToolFeedback.FeedbackResult.EDITED);
                default -> fbBuilder.result(InterruptionMetadata.ToolFeedback.FeedbackResult.REJECTED);
            }
            AgentTrace.approval(agentContext.getRunId(), 'A', handle.getName(), handle.getId(), audit);

            handledInterruption.addToolFeedback(fbBuilder.build());
            // 没什么问题就移除 pending
            pendingInterrupted.remove(handle.getId());
        }

        // 如果没有移除干净, 就打回重新写
        if (!pendingInterrupted.isEmpty()) {
            AgentTrace.approval(agentContext.getRunId(), 'Q', null, null,
                    "pending: " + pendingInterrupted.size() + " ; not-resumed");
            return Flux.just(new AgentStream(
                    AgentStream.Status.PERMISSION_REQUESTED,
                    approvalManager.toPermissionJson(pendingInterrupted.values().stream().toList())
            ));
        }
        AgentTrace.approval(agentContext.getRunId(), 'X', null, null, "resume: pending-cleared");

        InterruptionMetadata data = handledInterruption.build();
        // 恢复走的是无参 builder，context 是一张全新的表：累计量先取出来，建完再种回去，
        // 否则跨审批的"总量"会跟着旧表一起丢掉（只搬累计量，审批态故意不带过去）
        Map<String, Object> carriedTotals = carryTotals();
        // ponytail: metadata 里的 AgentContext 只在恢复后被 afterAgent / ModelPerformanceHook 读；
        // 之前用 addMetadata 写，那是 Builder 上的方法、返回新配置，这一行等于没写（HITL 的 feedback
        // 同理被丢掉，resume 是靠 HumanInTheLoopHook.run() 里的 threadId 取 checkpoint 恢复的，
        // 已实测），这里按 context 的写法补上。
        RunnableConfig newConfig = RunnableConfig.builder()
                .threadId(agentContext.getRunId())
                .addMetadata(RunnableConfig.HUMAN_FEEDBACK_METADATA_KEY, data)
                .build();
        config.context().remove(SessionEnum.HANDLED_INTERRUPTED.getCode());
        config.context().remove(SessionEnum.PENDING_INTERRUPTED.getCode());
        config = newConfig;
        config.context().put(SessionEnum.AGENT_CONTEXT.getCode(), agentContext);
        config.metadata().ifPresent(metadata -> metadata.put(SessionEnum.AGENT_CONTEXT.getCode(), agentContext));
        config.context().putAll(carriedTotals);
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
                    AgentTrace.approval(agentContext.getRunId(), 'S', feedback.getName(), feedback.getId(),
                            "decision: APPROVED ; reason: path ; args: " + feedback.getArguments());
                } else {
                    AgentTrace.approval(agentContext.getRunId(), 'S', feedback.getName(), feedback.getId(),
                            "decision: REJECTED ; reason: path-outside-workspace ; args: " + feedback.getArguments());
                }
            } else {
                // 检查 shell, 需要对可能的多重指令进行拆分并尝试进行模式匹配
                String command = extractShellCommand(feedback.getArguments());
                // 静态评估通过，或当前会话已经放行过这条命令/这类命令，则无需再人工审批
                if (approvalManager.checkCommandValid(command)) {
                    // TODO 自动放行/恢复执行
                    currFeedback.result(InterruptionMetadata.ToolFeedback.FeedbackResult.APPROVED);
                    AgentTrace.approval(agentContext.getRunId(), 'S', feedback.getName(), feedback.getId(),
                            "decision: APPROVED ; reason: allowlist ; command: " + command);
                } else if (approvalManager.isSessionApproved(command)) {
                    currFeedback.result(InterruptionMetadata.ToolFeedback.FeedbackResult.APPROVED);
                    AgentTrace.approval(agentContext.getRunId(), 'S', feedback.getName(), feedback.getId(),
                            "decision: APPROVED ; reason: session-cache ; command: " + command);
                } else {
                    AgentTrace.approval(agentContext.getRunId(), 'S', feedback.getName(), feedback.getId(),
                            "decision: ASK ; reason: deny-or-unknown ; command: " + command);
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
            AgentTrace.approval(agentContext.getRunId(), 'Q', null, null,
                    "pending: " + waitForHandles.size() + " ; " + waitForHandles.stream()
                            .map(InterruptionMetadata.ToolFeedback::getName)
                            .collect(Collectors.joining(",")));
            // 图停在中断点上，本轮到这里就结束了：afterAgent 不会执行、上游流也不会 complete
            // （实测 doOnComplete 直到恢复后才到，那时已经换了一轮），所以收口只能在这里做。
            // 这一轮此后不会再走 doOnComplete/doOnError，兜底收口不会重复。
            auditRunInterrupted(false);
            return Flux.just(new AgentStream(
                    AgentStream.Status.PERMISSION_REQUESTED,
                    approvalManager.toPermissionJson(waitForHandles)
            ));
        }

        // 全部自动放行（安全命令/会话缓存命中）：不打扰用户，直接恢复执行。
        // 本轮图已经停在中断点上、不会再往前走，所以先把运行槽让给恢复流，它才是接下来真正在跑的那个；
        // 延迟 1ms 是为了避免在 concatMap 处理中重入 run()
        Run holder = current.get();
        // 全部自动放行同样是"这一轮在中断点结束"，收口后再把槽让给恢复流
        auditRunInterrupted(false);
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
