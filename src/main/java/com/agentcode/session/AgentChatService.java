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

    public SessionStart startSession(String goal, String workspace, String requestId) {
        if (goal == null || goal.isBlank()) {
            throw new IllegalArgumentException("goal 不能为空");
        }
        String runId = UUID.randomUUID().toString();
        String normalizedWorkspace = runtimes.normalize(workspace).toString();
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

    public Submission chat(String runId, String content, String requestId) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("content 不能为空");
        }
        AgentSession session = session(runId);
        long afterSeq = session.log().head().seq();
        AgentSession.Task task = session.submit(idempotencyKey(requestId), content);
        return new Submission(afterSeq, task.turnId());
    }

    public Submission respond(
            String runId,
            String toolCallId,
            String decision,
            String arguments,
            String feedback) {
        AgentSession session = session(runId);
        PendingPermission pending = findPending(session, toolCallId);
        ToolUseBlock call = toolCall(session, pending.requestId());
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
        long afterSeq = session.log().head().seq();
        AgentSession.Task task = session.respond(pending.requestId(), answer);
        return new Submission(afterSeq, task.turnId());
    }

    public void stop(String runId) {
        AgentSession session = session(runId);
        if (!session.interrupt()) {
            throw new IllegalStateException("当前没有正在运行的回合");
        }
    }

    public void interrupt(String runId, String guidance) {
        AgentSession session = session(runId);
        if (guidance != null && !guidance.isBlank()) {
            session.steer(guidance);
        }
        if (!session.interrupt()) {
            throw new IllegalStateException("当前没有正在运行的回合");
        }
    }

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

    public List<PendingPermission> pending(String runId) {
        AgentSession session = session(runId);
        return pendingOf(session);
    }

    private Flux<AgentStream> poll(
            AgentSession session,
            long cursor,
            SessionEventProjector.State state,
            String expectedTurn) {
        return Mono.fromCallable(() -> read(session, cursor, state, expectedTurn))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(
                        batch -> {
                            Flux<AgentStream> frames = Flux.fromIterable(batch.frames());
                            if (batch.terminal()) {
                                return frames;
                            }
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

    public record SessionStart(String runId, long afterSeq, String turnId) {
    }

    public record Submission(long afterSeq, String turnId) {
    }

    private record Batch(long seq, List<AgentStream> frames, boolean terminal) {
    }

    private record Replay(List<AgentStream> frames, long cursor, boolean active) {
    }
}
