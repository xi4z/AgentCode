package com.agentcode.session;

import com.agentcode.agent.ShellTool;
import com.agentcode.agent.WorkspaceRuntime;
import com.agentcode.agent.WorkspaceRuntimeRegistry;
import com.agentcode.dto.AgentStream;
import com.agentcode.properties.AgentCodeProperties;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.session.SessionEvent;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.harness.agent.session.AgentSession;
import io.agentscope.harness.agent.session.SessionAnswer;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 会话入口：负责提交任务、恢复审批、读取持久事件流。
 *
 * <p>事件读取分两种模式：提交任务后按 {@code turnId} 轮询到该回合结束；{@code attach} 时按
 * {@code seq} 重放已提交历史。两种模式都只读 SessionLog，不会触发模型调用。
 */
@Service
public class AgentChatService {

    public static final String LOCAL_USER = "local";

    private final WorkspaceRuntimeRegistry runtimes;
    private final SessionMetadataStore metadataStore;
    private final Duration pollInterval;

    public AgentChatService(
            WorkspaceRuntimeRegistry runtimes,
            SessionMetadataStore metadataStore,
            AgentCodeProperties properties) {
        this.runtimes = runtimes;
        this.metadataStore = metadataStore;
        this.pollInterval = Duration.ofMillis(properties.getSession().getPollIntervalMillis());
    }

    /** 创建会话并提交首个任务；返回 runId、事件起点与回合标识。 */
    public SessionStart startSession(String goal, String workspace, String requestId) {
        if (goal == null || goal.isBlank()) {
            throw new IllegalArgumentException("goal 不能为空");
        }
        String runId = UUID.randomUUID().toString();
        String normalizedWorkspace = runtimes.normalize(workspace).toString();
        // 先落盘元数据：后面的 session() 会立即回读它来定位工作区。
        metadataStore.save(new SessionMetadata(runId, normalizedWorkspace, System.currentTimeMillis()));
        try {
            AgentSession session = session(runId);
            long afterSeq = session.log().head().seq();
            AgentSession.Task task = session.submit(idempotencyKey(requestId), goal);
            return new SessionStart(runId, afterSeq, task.turnId());
        } catch (RuntimeException error) {
            metadataStore.delete(runId);
            throw error;
        }
    }

    /** 向已有会话追加一条消息；requestId 用作提交幂等键。 */
    public Submission chat(String runId, String content, String requestId) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("content 不能为空");
        }
        AgentSession session = session(runId);
        // head 取在 submit 之前，因此轮询只会看到这条消息产生的新事件。
        long afterSeq = session.log().head().seq();
        AgentSession.Task task = session.submit(idempotencyKey(requestId), content);
        return new Submission(afterSeq, task.turnId());
    }

    /**
     * 回答一个挂起的工具审批。
     *
     * @param runId 会话标识
     * @param toolCallId 待审批工具调用标识
     * @param decision APPROVED、APPROVE_ALL、EDITED 或 REJECTED
     * @param arguments EDITED 时的新参数 JSON，其余决定可传 null
     * @param feedback REJECTED 时展示给模型的原因
     */
    public Submission respond(
            String runId,
            String toolCallId,
            String decision,
            String arguments,
            String feedback) {
        AgentSession session = session(runId);
        PendingPermission pending = findPending(session, toolCallId);
        ToolUseBlock call = toolCall(session, pending.requestId());
        // APPROVE_ALL 的规则写入 AgentState；其余决定只影响当前挂起的调用。
        SessionAnswer answer =
                switch (decision) {
                    case "APPROVED" -> new SessionAnswer.Approval(true, null);
                    case "APPROVE_ALL" -> {
                        rememberShellApproval(runId, call);
                        yield new SessionAnswer.Approval(true, null);
                    }
                    case "EDITED" -> edited(call, arguments);
                    case "REJECTED" -> new SessionAnswer.Approval(false, feedback);
                    default -> throw new IllegalArgumentException("未知 decision: " + decision);
                };
        // head 取在 respond 之前，恢复运行后从该位置继续读取。
        long afterSeq = session.log().head().seq();
        AgentSession.Task task = session.respond(pending.requestId(), answer);
        return new Submission(afterSeq, task.turnId());
    }

    /** 请求停止当前运行；没有运行中的回合时抛出异常。 */
    public void stop(String runId) {
        AgentSession session = session(runId);
        if (!session.interrupt()) {
            throw new IllegalStateException("当前没有正在运行的回合");
        }
    }

    /** 先写入引导语，再请求打断当前运行；引导语在下一个推理步骤生效。 */
    public void interrupt(String runId, String guidance) {
        AgentSession session = session(runId);
        if (guidance != null && !guidance.isBlank()) {
            session.steer(guidance);
        }
        if (!session.interrupt()) {
            throw new IllegalStateException("当前没有正在运行的回合");
        }
    }

    /**
     * 读取会话事件流。
     *
     * @param turnId 非空时只跟到该回合结束；为空时按 attach 语义重放已提交历史
     */
    public Flux<AgentStream> stream(String runId, long afterSeq, String turnId) {
        AgentSession session = session(runId);
        if (turnId != null) {
            return poll(session, afterSeq, SessionEventProjector.newState(), turnId);
        }
        return replay(session, afterSeq);
    }

    private Flux<AgentStream> replay(AgentSession session, long afterSeq) {
        return Mono.fromCallable(
                        () -> {
                            // attach 只重建已经提交的前缀，读取上限固定为进入方法时的 head。
                            long head = session.log().head().seq();
                            SessionEventProjector.State state = SessionEventProjector.newState();
                            List<AgentStream> frames = new ArrayList<>();
                            long cursor = afterSeq;
                            while (cursor < head) {
                                List<SessionEvent> batch = session.log().readAfter(cursor, 256);
                                if (batch.isEmpty()) {
                                    break;
                                }
                                long previous = cursor;
                                for (SessionEvent event : batch) {
                                    if (event.seq() > head) {
                                        break;
                                    }
                                    // 历史审批不重放；仍未解决的审批在末尾统一补一帧。
                                    if (!event.type().equals("interaction/requested")) {
                                        frames.addAll(
                                                SessionEventProjector.project(state, event)
                                                        .streams());
                                    }
                                    cursor = event.seq();
                                }
                                if (cursor == previous) {
                                    break;
                                }
                            }
                            if (!session.pending().isEmpty()) {
                                frames.add(
                                        new AgentStream(
                                                AgentStream.Status.PERMISSION_REQUESTED,
                                                "",
                                                cursor));
                            }
                            // lease 还在说明回合没有结束，重放历史后继续跟随。
                            boolean active =
                                    session.log().head().leaseUntil()
                                            > System.currentTimeMillis();
                            return new Replay(frames, cursor, active);
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(
                        replay -> {
                            Flux<AgentStream> history = Flux.fromIterable(replay.frames());
                            if (!replay.active()) {
                                return history;
                            }
                            return history.concatWith(
                                    poll(
                                            session,
                                            replay.cursor(),
                                            SessionEventProjector.newState(),
                                            null));
                        });
    }

    /** 当前未解决的审批请求，按 requestId 返回。 */
    public List<PendingPermission> pending(String runId) {
        AgentSession session = session(runId);
        return pendingOf(session);
    }

    private Flux<AgentStream> poll(
            AgentSession session,
            long cursor,
            SessionEventProjector.State state,
            String expectedTurn) {
        // 读取是阻塞 IO；每次读取放到 boundedElastic，避免占用 WebFlux 事件循环。
        return Mono.fromCallable(() -> read(session, cursor, state, expectedTurn))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(
                        batch -> {
                            Flux<AgentStream> frames = Flux.fromIterable(batch.frames());
                            if (batch.terminal()) {
                                return frames;
                            }
                            // 下游取消（连接关闭）会同时取消这里的延时链，执行本身不受影响。
                            return frames.concatWith(
                                    Mono.delay(pollInterval)
                                            .thenMany(
                                                    Flux.defer(
                                                            () ->
                                                                    poll(
                                                                            session,
                                                                            batch.seq(),
                                                                            state,
                                                                            expectedTurn))));
                        });
    }

    private Batch read(
            AgentSession session,
            long cursor,
            SessionEventProjector.State state,
            String expectedTurn) {
        List<SessionEvent> events = session.log().readAfter(cursor, 256);
        if (events.isEmpty()) {
            return new Batch(cursor, List.of(), false);
        }
        List<AgentStream> frames = new ArrayList<>();
        long seq = cursor;
        boolean terminal = false;
        for (SessionEvent event : events) {
            seq = event.seq();
            SessionEventProjector.Projection projection =
                    SessionEventProjector.project(state, event);
            frames.addAll(projection.streams());
            // 只认当前回合计入终止；队列里前一个回合的终止事件在这里被跳过。
            if (projection.terminal()
                    && (expectedTurn == null || expectedTurn.equals(event.turnId()))) {
                terminal = true;
                break;
            }
        }
        return new Batch(seq, frames, terminal);
    }

    private AgentSession session(String runId) {
        AgentSession session = runtime(runId).session(LOCAL_USER, runId);
        // 进程重启后，已接收但未派发的任务需要重新启动调度器；该方法幂等。
        session.start();
        return session;
    }

    private WorkspaceRuntime runtime(String runId) {
        SessionMetadata metadata =
                metadataStore
                        .find(runId)
                        .orElseThrow(() -> new SessionNotFoundException(runId));
        return runtimes.get(metadata.workspace());
    }

    private List<PendingPermission> pendingOf(AgentSession session) {
        List<PendingPermission> result = new ArrayList<>();
        session.pending()
                .forEach(
                        (requestId, event) -> {
                            ToolUseBlock call = callOf(event);
                            if (call == null) {
                                return;
                            }
                            Object description = event.data().get("description");
                            result.add(
                                    new PendingPermission(
                                            requestId,
                                            call.getId(),
                                            call.getName(),
                                            JsonUtils.getJsonCodec().toJson(call.getInput()),
                                            description == null
                                                    ? "该工具调用需要人工审批"
                                                    : String.valueOf(description)));
                        });
        return result;
    }

    private PendingPermission findPending(AgentSession session, String toolCallId) {
        for (PendingPermission permission : pendingOf(session)) {
            if (java.util.Objects.equals(permission.toolCallId(), toolCallId)) {
                return permission;
            }
        }
        throw new IllegalArgumentException("没有待审批的工具调用: " + toolCallId);
    }

    private ToolUseBlock toolCall(AgentSession session, String requestId) {
        SessionEvent event = session.pending().get(requestId);
        if (event == null) {
            throw new IllegalArgumentException("审批请求已失效: " + requestId);
        }
        ToolUseBlock call = callOf(event);
        if (call == null) {
            throw new IllegalStateException("审批请求缺少工具调用信息: " + requestId);
        }
        return call;
    }

    private void rememberShellApproval(String runId, ToolUseBlock call) {
        if (!ShellTool.NAME.equals(call.getName())) {
            return;
        }
        Object command = call.getInput() == null ? null : call.getInput().get("command");
        if (command == null || String.valueOf(command).isBlank()) {
            return;
        }
        io.agentscope.core.ReActAgent delegate = runtime(runId).agent().getDelegate();
        // ConfirmResult 里的规则只作用于当前 PermissionEngine 实例；要跨回合保留，
        // 必须把规则写回会话对应的 PermissionContextState。
        PermissionContextState current =
                delegate.getAgentState(LOCAL_USER, runId).getPermissionContext();
        PermissionContextState.Builder builder =
                PermissionContextState.builder().mode(current.getMode());
        current.getWorkingDirectories().forEach(builder::addWorkingDirectory);
        current.getAllowRules()
                .forEach((tool, rules) -> rules.forEach(rule -> builder.addAllowRule(tool, rule)));
        current.getDenyRules()
                .forEach((tool, rules) -> rules.forEach(rule -> builder.addDenyRule(tool, rule)));
        current.getAskRules()
                .forEach((tool, rules) -> rules.forEach(rule -> builder.addAskRule(tool, rule)));
        builder.addAllowRule(
                ShellTool.NAME,
                new PermissionRule(
                        ShellTool.NAME,
                        String.valueOf(command),
                        PermissionBehavior.ALLOW,
                        "session"));
        delegate.replacePermissionContext(LOCAL_USER, runId, builder.build());
    }

    private SessionAnswer edited(ToolUseBlock call, String arguments) {
        if (arguments == null || arguments.isBlank()) {
            throw new IllegalArgumentException("EDITED 需要新的参数");
        }
        Map<String, Object> input =
                JsonUtils.getJsonCodec()
                        .fromJson(
                                arguments,
                                new com.fasterxml.jackson.core.type.TypeReference<
                                        Map<String, Object>>() {
                                });
        ToolUseBlock edited =
                ToolUseBlock.builder()
                        .id(call.getId())
                        .name(call.getName())
                        .input(input)
                        // content 携带参数 JSON 片段，执行器与持久事件都会用到它。
                        .content(JsonUtils.getJsonCodec().toJson(input))
                        .build();
        return new SessionAnswer.Confirmation(
                new ConfirmResult(true, edited, null, "edited by user"));
    }

    private static ToolUseBlock callOf(SessionEvent event) {
        Object rawCall = event.data().get("call");
        if (rawCall == null) {
            return null;
        }
        return JsonUtils.getJsonCodec().convertValue(rawCall, ToolUseBlock.class);
    }

    private static String idempotencyKey(String requestId) {
        return requestId == null || requestId.isBlank()
                ? UUID.randomUUID().toString()
                : requestId;
    }

    /** 新会话的返回：runId、事件起点、首个回合。 */
    public record SessionStart(String runId, long afterSeq, String turnId) {
    }

    /** 提交或恢复之后的返回：事件起点与目标回合。 */
    public record Submission(long afterSeq, String turnId) {
    }

    /** 一次事件读取的结果；terminal 表示目标回合已经结束。 */
    private record Batch(long seq, List<AgentStream> frames, boolean terminal) {
    }

    /** attach 重放结果；active 表示重放结束后是否还要继续跟随。 */
    private record Replay(List<AgentStream> frames, long cursor, boolean active) {
    }
}
