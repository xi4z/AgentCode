package com.agentcode.agent;

import com.agentcode.common.ShellParseHelper;
import com.agentcode.context.AgentContext;
import com.agentcode.exception.AgentAlreadyRunningException;
import com.agentcode.exception.InterruptFailException;
import com.agentcode.exception.StopFailException;
import com.agentcode.exception.TaskNotFoundException;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.action.InterruptionMetadata;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.extension.tools.filesystem.FileSystemTools;
import com.alibaba.cloud.ai.graph.agent.hook.Hook;
import com.alibaba.cloud.ai.graph.agent.hook.hip.HumanInTheLoopHook;
import com.alibaba.cloud.ai.graph.agent.hook.hip.ToolConfig;
import com.alibaba.cloud.ai.graph.agent.hook.shelltool.ShellToolAgentHook;
import com.alibaba.cloud.ai.graph.agent.hook.skills.SkillsAgentHook;
import com.alibaba.cloud.ai.graph.agent.tools.GlobSearchTool;
import com.alibaba.cloud.ai.graph.agent.tools.GrepSearchTool;
import com.alibaba.cloud.ai.graph.agent.tools.ShellTool2;
import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.alibaba.cloud.ai.graph.skills.registry.SkillRegistry;
import com.alibaba.cloud.ai.graph.skills.registry.filesystem.FileSystemSkillRegistry;
import com.alibaba.cloud.ai.graph.streaming.OutputType;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.agentcode.common.ShellParseHelper.*;

public class AgentSession {

    // 检测 bash 命令是否操作 cwd 之外路径的正则规则列表（强制触发 ASK，不可被 allow 名单绕过）
    private static final List<Pattern> OUTSIDE_CWD_PATTERNS = List.of(
            Pattern.compile("(^|\\s)/[^\\s]"),              // absolute path
            Pattern.compile("(^|\\s)~"),                    // tilde home
            Pattern.compile("(^|\\s)\\.\\.(/|$|\\s)"),      // parent traversal
            Pattern.compile("\\$\\{?HOME\\b"),              // $HOME variable
            Pattern.compile("\\$\\{?PWD\\b"),               // $PWD variable
            Pattern.compile("(^|\\s|;|&&|\\|\\|)cd(\\s|$)") // explicit cd
    );

    public AgentSession(AgentContext agentContext, ChatModel chatModel, BaseCheckpointSaver saver) {
        this(agentContext, chatModel, saver, List.of("shell", "write_file"));
    }

    public AgentSession(AgentContext agentContext, ChatModel chatModel, BaseCheckpointSaver saver,
                        List<String> approvalTools) {
        this.agentContext = agentContext;
        this.saver = saver;
        this.approvalTools = approvalTools == null ? List.of() : List.copyOf(approvalTools);
        this.approvalManager = new AgentApprovalManager(agentContext);
        String workspace = resolveWorkspace(agentContext);
        this.shellTool2 = ShellTool2.builder(workspace).build();

        List<Hook> hooks = new ArrayList<>();
        hooks.add(ShellToolAgentHook.builder()
                .shellTool2(shellTool2)
                .shellToolName("shell")
                .build());

        // 需要人工审批的工具通过 HumanInTheLoopHook 在调用前中断
        if (!this.approvalTools.isEmpty()) {
            HumanInTheLoopHook.Builder hitlBuilder = HumanInTheLoopHook.builder();
            for (String tool : this.approvalTools) {
                hitlBuilder.approvalOn(tool, ToolConfig.builder()
                        .description("该工具调用需要人工审批")
                        .build());
            }
            hooks.add(hitlBuilder.build());
        }

        // 组装 SKill 加载器
        SkillRegistry registry = FileSystemSkillRegistry.builder()
                .projectSkillsDirectory(workspace+ "/skills")
                .build();

        SkillsAgentHook skillsHook = SkillsAgentHook.builder()
                .skillRegistry(registry)
                .build();

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
                .hooks(hooks)
                .hooks(skillsHook)
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

    final BaseCheckpointSaver saver;
    final AgentContext agentContext;
    final ReactAgent reactAgent;
    final List<String> approvalTools;
    final ShellTool2 shellTool2;
    final AgentApprovalManager approvalManager;
    RunnableConfig config;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @AllArgsConstructor
    @Data
    private static class RunningTask{
        Disposable disposable;
        Sinks.Many<AgentStream> Sink;
    }
    private volatile RunningTask runningTask;

    // 当前会话已放行的命令缓存：精确命令 + 通配符模式（仅内存，不持久化）
    private final Set<String> sessionApprovedCommands = ConcurrentHashMap.newKeySet();
    private final List<Pattern> sessionApprovedPatterns = new CopyOnWriteArrayList<>();

    public Flux<AgentStream> run(String goal){
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
        Disposable disposable;
        try {
            // 2. 启动内部 Agent，把事件转发到 sink
            disposable = reactAgent.stream(goal, runConfig).concatMap(this::classifyMessage)
                    .doOnNext(sink::tryEmitNext)
                    .doOnComplete(() -> {
                        sink.tryEmitComplete();
                        synchronized (this) {
                            runningTask = null;
                            // 审批中断时保留 INTERRUPTED 状态，等待 handleAgentInterrupt 恢复
                            if (status != Status.INTERRUPTED) {
                                status = Status.FREE;
                            }
                        }
                    })
                    .doOnError(error -> {
                        sink.tryEmitError(error);
                        synchronized (this) {
                            runningTask = null;
                            if (status != Status.INTERRUPTED) {
                                status = Status.FREE;
                            }
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

    public Flux<AgentStream> handleAgentInterrupt(AgentInterruptHandle[] handles) {
        synchronized (this) {
            if (status != Status.INTERRUPTED) {
                throw new InterruptFailException("会话: " + this.agentContext.getRunId() + "恢复中断失败, 因为当前会话没有在被中断");
            }
        }
        Object raw = config.context().get("__HANDLES_INTERRUPTED__");
        if (!(raw instanceof InterruptionMetadata.Builder handledInterruption)) {
            throw new IllegalStateException("会话: " + this.agentContext.getRunId() + "没有待处理的审批上下文");
        }
        Map<String, InterruptionMetadata.ToolFeedback> pendingInterrupted = (Map<String, InterruptionMetadata.ToolFeedback>) config.context().get("__PENDING_INTERRUPTED");
        for (AgentInterruptHandle handle : handles) {
            InterruptionMetadata.ToolFeedback original = pendingInterrupted.get(handle.getId());
            String originalArguments = original == null ? handle.getArguments() : original.getArguments();

            InterruptionMetadata.ToolFeedback.Builder fbBuilder = InterruptionMetadata.ToolFeedback.builder()
                    .name(handle.getName())
                    .id(handle.getId())
                    .description(handle.getDescription() != null ? handle.getDescription() : (original == null ? null : original.getDescription()))
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
        }

        InterruptionMetadata data = handledInterruption.build();
        RunnableConfig newConfig = RunnableConfig.builder()
                .threadId(agentContext.getRunId())
                .addMetadata(RunnableConfig.HUMAN_FEEDBACK_METADATA_KEY, data)
                .build();
        config.context().remove("__HANDLES_INTERRUPTED__");
        config.context().remove("__PENDING_INTERRUPTED");
        // 第一次流中断后 ShellToolAgentHook 会清理会话，恢复前需要重新初始化 shell session
        shellTool2.getSessionManager().initialize(newConfig);
        return run("", newConfig);
    }

    private String resolveWorkspace(AgentContext agentContext) {
        String workspace = agentContext.getWorkspace();
        if (workspace == null || workspace.isBlank()) {
            return System.getProperty("user.dir");
        }
        return workspace;
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
    private Flux<AgentStream> preHandleAgentInterrupt(InterruptionMetadata metadata) {
        status = Status.INTERRUPTED;
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
        config.context().put("__HANDLES_INTERRUPTED__", handledInterruption);
        // 拿到需要处理审批的请求原始数据, 并以 id 做键区分
        config.context().put("__PENDING_INTERRUPTED__", waitForHandles.stream().collect(Collectors.toMap(InterruptionMetadata.ToolFeedback::getId, Function.identity())));

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

    /**
     * 将待审批的工具反馈列表序列化为前端可读的 JSON
     */
    private String toPermissionJson(List<InterruptionMetadata.ToolFeedback> feedbacks) {
        List<Map<String, String>> items = new ArrayList<>();
        for (InterruptionMetadata.ToolFeedback feedback : feedbacks) {
            Map<String, String> item = new HashMap<>();
            item.put("toolCallId", feedback.getId());
            item.put("toolName", feedback.getName());
            item.put("arguments", feedback.getArguments());
            item.put("description", feedback.getDescription());
            items.add(item);
        }
        try {
            return objectMapper.writeValueAsString(items);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }


    /**
     * 检查 Shell 参数：解析 command 后交给静态评估
     * @param feedback
     * @return true 表示静态评估通过（可自动放行），false 表示需要人工审批/拒绝
     */
    private boolean checkShellValid(InterruptionMetadata.ToolFeedback feedback) {
        return checkCommandValid(extractShellCommand(feedback.getArguments()));
    }

    /**
     * bash 命令静态评估：返回是否允许自动放行。
     * 移植自 Python 分支 permission/policy.py：
     * deny_patterns → outside-cwd 强制 ASK → allow_patterns → default(ASK)
     */
    private boolean checkCommandValid(String command) {
        if (command == null || command.isBlank()) {
            return false;
        }

        // 复合命令（| ; && ||）拆成多个子命令，只要有一个不满足就整体不自动放行
        for (String segment : splitShellSegments(command)) {
            if (!checkSingleCommandValid(segment)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 单条命令的静态评估：命中黑名单/越界则 false，命中安全名单则 true，否则默认 false
     */
    private boolean checkSingleCommandValid(String segment) {
        if (segment == null || segment.isBlank()) {
            return false;
        }

        // outside-cwd 强制 ASK，不允许被安全名单绕过
        if (matchesOutsideCwd(segment)) {
            return false;
        }

        List<String> tokens = ShellParseHelper.splitCommand(segment);
        if (tokens.isEmpty()) {
            return false;
        }
        String commandName = tokens.get(0);

        // deny_patterns：危险命令黑名单，命中不允许自动放行
        if (ShellParseHelper.DANGEROUS_COMMANDS.contains(commandName)) {
            return false;
        }

        // allow_patterns：安全命令名单，命中自动放行
        if (ShellParseHelper.safeCommands.contains(commandName)) {
            return true;
        }

        // 默认策略：bash 默认 ASK，未命中任何名单时交给人工审批
        return false;
    }



    /**
     * 判断命令是否命中 outside-cwd 启发式规则
     */
    private boolean matchesOutsideCwd(String command) {
        for (Pattern pattern : OUTSIDE_CWD_PATTERNS) {
            if (pattern.matcher(command).find()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断命令是否已在当前会话中被批准（精确命令或通配符模式）
     */
    public boolean isSessionApproved(String command) {
        return approvalManager.isSessionApproved(command);
    }

    /**
     * 记录当前会话放行一条具体命令
     */
    public void approveCommandForSession(String command) {
        approvalManager.approveCommandForSession(command);
    }

    /**
     * 记录当前会话放行一类命令（shell 通配符，* 匹配任意串，? 匹配单个字符）
     */
    public void approvePatternForSession(String pattern) {
        approvalManager.approvePatternForSession(pattern);
    }
}
