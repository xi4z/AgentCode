package com.agentcode.session;

import com.agentcode.common.SessionConfigKeys;
import com.agentcode.context.AgentContext;
import com.agentcode.dto.AgentApprovalManager;
import com.agentcode.dto.AgentInterruptHandle;
import com.agentcode.dto.AgentStream;
import com.agentcode.exception.AgentAlreadyRunningException;
import com.agentcode.exception.InterruptFailException;
import com.agentcode.exception.StopFailException;
import com.agentcode.exception.TaskNotFoundException;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.action.InterruptionMetadata;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.tools.ShellTool2;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.alibaba.cloud.ai.graph.streaming.OutputType;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.agentcode.common.ShellParseHelper.extractShellCommand;

@Slf4j
public class AgentSession {

    public enum Status{
        FREE, // 当前会话没有在运行
        RUNNING, // 当前会话正在运行
        INTERRUPTED // 当前会话被中断, 出现这种状态的原因通常是 Agent 正在等待用户审批
    }

    @Getter
    private volatile Status status = Status.FREE; // 会话状态

    /** 进入 INTERRUPTED（等待人工审批）的时间戳，0 表示不在等待 */
    @Getter
    private volatile long approvalWaitSinceEpochMs;

    private final AgentContext agentContext;
    private final ReactAgent reactAgent;
    private final ShellTool2 shellTool2;
    private final AgentApprovalManager approvalManager;

    private volatile RunnableConfig config;

    @AllArgsConstructor
    @Data
    private static class RunningTask{
        Disposable disposable;
        Sinks.Many<AgentStream> Sink;
    }
    private volatile RunningTask runningTask;

    public AgentSession(AgentContext agentContext, AgentSessionRuntime runtime) {
        this.agentContext = agentContext;
        this.reactAgent = runtime.getReactAgent();
        this.shellTool2 = runtime.getShellTool2();
        this.approvalManager = runtime.getApprovalManager();
        this.config = runtime.getInitialConfig();
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
        Sinks.Many<AgentStream> sink;
        synchronized (this) {
            if (status == Status.RUNNING) {
                // 此时不允许run
                throw new AgentAlreadyRunningException("会话:" + agentContext.getRunId() + "正在运行");
            }
            status = Status.RUNNING;
            sink = Sinks.many()
                    .unicast()
                    .onBackpressureBuffer();
        }
        long runStartNanos = System.nanoTime();
        AtomicInteger eventCount = new AtomicInteger();
        AtomicInteger toolEventCount = new AtomicInteger();
        AtomicInteger permissionCount = new AtomicInteger();
        log.info(
                "AUDIT_AGENT_RUN_START runId={} goal={} workspace={}",
                agentContext.getRunId(),
                goal,
                agentContext.getWorkspace()
        );
        Disposable disposable;
        try {
            // 2. 启动内部 Agent，把事件转发到 sink
            disposable = reactAgent.stream(goal, runConfig).concatMap(this::classifyMessage)
                    .doOnNext(stream -> {
                        eventCount.incrementAndGet();
                        if (stream.status() == AgentStream.Status.TOOL_STREAMING
                                || stream.status() == AgentStream.Status.TOOL_FINISHED) {
                            toolEventCount.incrementAndGet();
                        } else if (stream.status() == AgentStream.Status.PERMISSION_REQUESTED) {
                            permissionCount.incrementAndGet();
                        }
                    })
                    .doOnNext(sink::tryEmitNext)
                    .doOnComplete(() -> {
                        synchronized (this) {
                            runningTask = null;
                            // 审批中断时保留 INTERRUPTED 状态，等待 handleAgentInterrupt 恢复
                            if (status != Status.INTERRUPTED) {
                                status = Status.FREE;
                            }
                        }
                        logAgentRun(runStartNanos, agentContext.getRunId(), goal, "COMPLETED",
                                eventCount.get(), toolEventCount.get(), permissionCount.get(), null);
                        sink.tryEmitComplete();
                    })
                    .doOnError(error -> {
                        synchronized (this) {
                            runningTask = null;
                            if (status != Status.INTERRUPTED) {
                                status = Status.FREE;
                            }
                        }
                        logAgentRun(runStartNanos, agentContext.getRunId(), goal, "ERROR",
                                eventCount.get(), toolEventCount.get(), permissionCount.get(),
                                error.getMessage());
                        sink.tryEmitError(error);
                    })
                    .subscribe();
        } catch (GraphRunnerException e) {
            logAgentRun(runStartNanos, agentContext.getRunId(), goal, "ERROR",
                    eventCount.get(), toolEventCount.get(), permissionCount.get(), e.getMessage());
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
                if (status == Status.INTERRUPTED) {
                    // 正在等待人工审批：已经没有可 dispose 的任务，清掉审批上下文并放弃本轮
                    clearApprovalState();
                    status = Status.FREE;
                    log.info("会话 {} 在等待审批期间被停止，已放弃本轮待审批工具调用", agentContext.getRunId());
                    return;
                }
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

    /**
     * 丢弃当前挂在 config.context 上的审批状态（已放行的 feedback、待审批列表、已提交的决定）。
     */
    private void clearApprovalState() {
        config.context().remove(SessionConfigKeys.HANDLED_INTERRUPTION);
        config.context().remove(SessionConfigKeys.PENDING_INTERRUPTIONS);
        config.context().remove(SessionConfigKeys.PENDING_RESPONSES);
        approvalWaitSinceEpochMs = 0;
    }

    /**
     * 本轮审批是否已经等待超过 maxWait。
     */
    public boolean isApprovalWaitExpired(Duration maxWait) {
        if (maxWait == null || maxWait.isZero() || maxWait.isNegative()) {
            return false;
        }
        long since = approvalWaitSinceEpochMs;
        return status == Status.INTERRUPTED
                && since > 0
                && System.currentTimeMillis() - since >= maxWait.toMillis();
    }

    /**
     * 放弃超时仍未答复的审批：清理审批上下文并把会话置回空闲，
     * 避免客户端掉线后会话永久卡在 INTERRUPTED。
     *
     * @return true 表示确实放弃了一轮待审批
     */
    public boolean abandonStaleApproval(Duration maxWait) {
        if (!isApprovalWaitExpired(maxWait)) {
            return false;
        }
        List<String> abandoned;
        synchronized (this) {
            if (status != Status.INTERRUPTED) {
                return false;
            }
            abandoned = new ArrayList<>(pendingFeedbacks().keySet());
            clearApprovalState();
            status = Status.FREE;
        }
        log.warn("AUDIT_APPROVAL_TIMEOUT runId={} abandonedToolCalls={} maxWaitMs={}",
                agentContext.getRunId(), abandoned, maxWait.toMillis());
        return true;
    }

    public void interrupt(String message) {
        synchronized (this) {
            if (status != Status.RUNNING || runningTask == null) {
                throw new InterruptFailException("会话: " + this.agentContext.getRunId() + "打断失败, 因为当前会话没有在进行中");
            }
        }
        reactAgent.interrupt(message, config);
    }

    /**
     * 提交人工审批决定，并在本轮待审批项全部答复后恢复执行。
     *
     * <p>一轮 interruption 可能同时挂起多个工具（例如 shell + write_file），而框架恢复时
     * 需要一次性给出全部 feedback。因此这里按 toolCallId 缓存决定：没答复齐时只回一个
     * {@link AgentStream.Status#PERMISSION_PENDING} 事件并保持 INTERRUPTED，
     * 答复齐了才真正重建 metadata 并续跑。
     */
    public Flux<AgentStream> handleAgentInterrupt(AgentInterruptHandle[] handles) {
        synchronized (this) {
            if (status != Status.INTERRUPTED) {
                throw new InterruptFailException("会话: " + this.agentContext.getRunId() + "恢复中断失败, 因为当前会话没有在被中断");
            }
        }
        Object raw = config.context().get(SessionConfigKeys.HANDLED_INTERRUPTION);
        if (!(raw instanceof InterruptionMetadata.Builder)) {
            throw new IllegalStateException("会话: " + this.agentContext.getRunId() + "没有待处理的审批上下文");
        }
        Map<String, InterruptionMetadata.ToolFeedback> pendingInterrupted = pendingFeedbacks();
        Map<String, AgentInterruptHandle> responses = recordedResponses();

        AgentInterruptHandle[] submitted = handles == null ? new AgentInterruptHandle[0] : handles;
        for (AgentInterruptHandle handle : submitted) {
            if (handle == null || handle.getId() == null || handle.getId().isBlank()) {
                throw new InterruptFailException("会话: " + this.agentContext.getRunId() + "审批决定缺少 toolCallId");
            }
            if (!pendingInterrupted.containsKey(handle.getId())) {
                throw new InterruptFailException("审批项 " + handle.getId() + " 不在会话 "
                        + this.agentContext.getRunId() + " 的待审批列表中");
            }
            responses.put(handle.getId(), handle);
        }

        List<String> remaining = pendingInterrupted.keySet().stream()
                .filter(id -> !responses.containsKey(id))
                .toList();
        if (!remaining.isEmpty()) {
            log.info("会话 {} 仍有 {} 个审批项未答复: {}",
                    agentContext.getRunId(), remaining.size(), remaining);
            return Flux.just(new AgentStream(
                    AgentStream.Status.PERMISSION_PENDING,
                    approvalManager.toPendingIdsJson(remaining)
            ));
        }
        return resumeInterruptedRun();
    }

    /**
     * 本轮正在等待人工答复的工具反馈（toolCallId -> 原始 feedback）
     */
    @SuppressWarnings("unchecked")
    private Map<String, InterruptionMetadata.ToolFeedback> pendingFeedbacks() {
        Object raw = config.context().get(SessionConfigKeys.PENDING_INTERRUPTIONS);
        return raw instanceof Map ? (Map<String, InterruptionMetadata.ToolFeedback>) raw : Map.of();
    }

    /**
     * 本轮已收到的审批决定（toolCallId -> handle），随 config 一起在本轮恢复后清理
     */
    @SuppressWarnings("unchecked")
    private Map<String, AgentInterruptHandle> recordedResponses() {
        Object raw = config.context().get(SessionConfigKeys.PENDING_RESPONSES);
        if (raw instanceof Map) {
            return (Map<String, AgentInterruptHandle>) raw;
        }
        Map<String, AgentInterruptHandle> responses = new ConcurrentHashMap<>();
        config.context().put(SessionConfigKeys.PENDING_RESPONSES, responses);
        return responses;
    }

    /**
     * 用收集到的决定重建 InterruptionMetadata 并续跑会话。
     */
    private Flux<AgentStream> resumeInterruptedRun() {
        InterruptionMetadata.Builder handledInterruption = (InterruptionMetadata.Builder)
                config.context().get(SessionConfigKeys.HANDLED_INTERRUPTION);
        Map<String, InterruptionMetadata.ToolFeedback> pendingInterrupted = pendingFeedbacks();
        Map<String, AgentInterruptHandle> responses = recordedResponses();

        for (Map.Entry<String, InterruptionMetadata.ToolFeedback> entry : pendingInterrupted.entrySet()) {
            InterruptionMetadata.ToolFeedback original = entry.getValue();
            AgentInterruptHandle handle = responses.get(entry.getKey());
            if (handle == null) {
                // 理论上不会发生（答复齐才恢复），兜底按拒绝处理，绝不默认放行
                handle = new AgentInterruptHandle(agentContext.getRunId(), entry.getKey(), original.getName(),
                        original.getArguments(), original.getDescription(), AgentInterruptHandle.Decision.REJECTED, null);
            }
            String originalArguments = original.getArguments();
            AgentInterruptHandle.Decision decision = handle.getDecision() == null
                    ? AgentInterruptHandle.Decision.REJECTED
                    : handle.getDecision();

            InterruptionMetadata.ToolFeedback.Builder fbBuilder = InterruptionMetadata.ToolFeedback.builder()
                    .name(handle.getName() != null ? handle.getName() : original.getName())
                    .id(entry.getKey())
                    .description(handle.getDescription() != null ? handle.getDescription() : original.getDescription())
                    .arguments(approvalManager.resolveArguments(handle, originalArguments));

            switch (decision) {
                case APPROVED -> fbBuilder.result(InterruptionMetadata.ToolFeedback.FeedbackResult.APPROVED);
                case APPROVE_ALL -> {
                    fbBuilder.result(InterruptionMetadata.ToolFeedback.FeedbackResult.APPROVED);
                    approvalManager.rememberApproval(handle, originalArguments);
                }
                case EDITED -> fbBuilder.result(InterruptionMetadata.ToolFeedback.FeedbackResult.EDITED);
                default -> fbBuilder.result(InterruptionMetadata.ToolFeedback.FeedbackResult.REJECTED);
            }

            handledInterruption.addToolFeedback(fbBuilder.build());
        }

        InterruptionMetadata data = handledInterruption.build();
        RunnableConfig newConfig = RunnableConfig.builder()
                .threadId(agentContext.getRunId())
                .addMetadata(RunnableConfig.HUMAN_FEEDBACK_METADATA_KEY, data)
                .build();
        config.context().remove(SessionConfigKeys.HANDLED_INTERRUPTION);
        config.context().remove(SessionConfigKeys.PENDING_INTERRUPTIONS);
        config.context().remove(SessionConfigKeys.PENDING_RESPONSES);
        approvalWaitSinceEpochMs = 0;
        config = newConfig;
        config.context().put(SessionConfigKeys.AGENT_CONTEXT, agentContext);
        // 第一次流中断后 ShellToolAgentHook 会清理会话，恢复前需要重新初始化 shell session
        shellTool2.getSessionManager().initialize(newConfig);
        return run("");
    }

    private void logAgentRun(long runStartNanos, String runId, String goal, String result,
                             int eventCount, int toolEventCount, int permissionCount, String error) {
        long durationMs = (System.nanoTime() - runStartNanos) / 1_000_000;
        if (result == null || "ERROR".equals(result)) {
            log.warn(
                    "AUDIT_AGENT_RUN runId={} goal={} result={} durationMs={} events={} toolEvents={} permissionRequests={} error={}",
                    runId, goal, result, durationMs, eventCount, toolEventCount, permissionCount, error
            );
        } else {
            log.info(
                    "AUDIT_AGENT_RUN runId={} goal={} result={} durationMs={} events={} toolEvents={} permissionRequests={} error={}",
                    runId, goal, result, durationMs, eventCount, toolEventCount, permissionCount, error
            );
        }
    }

    private Flux<AgentStream> classifyMessage(NodeOutput nodeOutput) {
        if (nodeOutput instanceof InterruptionMetadata metadata) {
            return preHandleAgentInterrupt(metadata);
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
     * Human-in-the-loop 审批预处理：把一轮 interruption 的工具调用分成"自动放行"和"需要人工"两批。
     *
     * <p>用户对每个工具可以给出四种决定：
     * <ol>
     *   <li>APPROVED —— 同意本次调用</li>
     *   <li>APPROVE_ALL —— 本会话内放行这条精确命令（黑名单与 deny 名单仍然会再次询问）</li>
     *   <li>REJECTED —— 拒绝</li>
     *   <li>EDITED —— 用前端回传的新参数执行</li>
     * </ol>
     *
     * <p>自动放行的判定顺序（策略见 {@link com.agentcode.dto.ApprovalPolicy}）：
     * <ol>
     *   <li>复合命令按 | ; &amp;&amp; 拆分，逐段评估，有一段不过就整体交人工</li>
     *   <li>命中 deny 通配符或危险命令黑名单 → 人工</li>
     *   <li>shell 命令触及工作区之外（绝对路径、~、..、$HOME、显式 cd）→ 人工</li>
     *   <li>命中 allow 通配符或安全命令名单 → 放行</li>
     *   <li>文件类工具路径在工作区内 → 放行</li>
     *   <li>会话级 APPROVE_ALL 缓存命中 → 放行</li>
     * </ol>
     *
     * <p>需要人工的部分通过 {@code PERMISSION_REQUESTED} 事件推给前端（WebSocket 侧转成
     * {@code permission_requested}），并停在 INTERRUPTED 等答复；全部自动放行时不打扰用户直接续跑。
     */
    private Flux<AgentStream> preHandleAgentInterrupt(InterruptionMetadata metadata) {
        // 与其他状态迁移保持一致：状态写入必须在 this 锁内，否则会与 run()/stop() 的判定竞态
        synchronized (this) {
            status = Status.INTERRUPTED;
            approvalWaitSinceEpochMs = System.currentTimeMillis();
        }
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
                // 非 shell 的文件类工具：路径仍在工作区内即可放行，越界则交人工
                if (approvalManager.checkPathValid(feedback.getArguments())) {
                    currFeedback.result(InterruptionMetadata.ToolFeedback.FeedbackResult.APPROVED);
                }
            } else {
                // shell：先按 | ; && 拆成子命令做静态评估，其次看会话级 APPROVE_ALL 缓存
                String command = extractShellCommand(feedback.getArguments());
                if (approvalManager.checkCommandValid(command) || approvalManager.isSessionApproved(command)) {
                    currFeedback.result(InterruptionMetadata.ToolFeedback.FeedbackResult.APPROVED);
                }
            }
            InterruptionMetadata.ToolFeedback fb = currFeedback.build();
            if (fb.getResult() == null){
                waitForHandles.add(fb);
            }else {
                handledInterruption.addToolFeedback(fb); // 否则就增加到已就绪的 fb 中
            }
        }
        config.context().put(SessionConfigKeys.HANDLED_INTERRUPTION, handledInterruption);
        // 拿到需要处理审批的请求原始数据, 并以 id 做键区分（保留插入顺序，恢复时按同一顺序回放）
        config.context().put(SessionConfigKeys.PENDING_INTERRUPTIONS, waitForHandles.stream()
                .collect(Collectors.toMap(InterruptionMetadata.ToolFeedback::getId, Function.identity(),
                        (a, b) -> a, LinkedHashMap::new)));
        // 新一轮审批不继承上一轮未答复齐的决定
        config.context().remove(SessionConfigKeys.PENDING_RESPONSES);

        // 有需要人工审批的工具时，发送 permission.requested 给前端并中断当前流
        if (!waitForHandles.isEmpty()) {
            return Flux.just(new AgentStream(
                    AgentStream.Status.PERMISSION_REQUESTED,
                    approvalManager.toPermissionJson(waitForHandles)
            ));
        }

        // 全部自动放行（安全命令/会话缓存命中）：不打扰用户，直接恢复执行
        // 延迟到当前流完成后再启动恢复流，避免在 concatMap 处理中重入 run()
        return Flux.defer(() ->
                Mono.delay(java.time.Duration.ofMillis(1))
                        .flatMapMany(ignore -> handleAgentInterrupt(new AgentInterruptHandle[0]))
        );
    }
}
