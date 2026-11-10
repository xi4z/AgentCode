package com.agentcode.session;

import com.agentcode.dto.AgentStream;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.session.SessionEvent;
import io.agentscope.core.util.JsonUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 把 AgentScope 的持久会话事件投影成前端可消费的帧。
 */
public final class SessionEventProjector {

    private SessionEventProjector() {
    }

    public static final class State {
        private final Set<String> announcedTools = new HashSet<>();
        private final Set<String> finishedTools = new HashSet<>();
    }

    public static State newState() {
        return new State();
    }

    public static Projection project(State state, SessionEvent event) {
        List<AgentStream> streams = new ArrayList<>();
        boolean terminal = false;
        long seq = event.seq();

        switch (event.type()) {
            case "turn/start" ->
                    streams.add(
                            new AgentStream(AgentStream.Status.TURN_STARTED, "", seq));
            case "model/chunk" -> projectModelChunk(state, event, streams);
            case "message/assistant" -> projectAssistantMessage(event, streams);
            case "tool/requested" -> projectToolRequested(state, event, streams);
            case "action/end", "tool/result" -> {
                Object toolCallId = event.data().get("toolCallId");
                if (toolCallId == null && event.data().get("result") instanceof java.util.Map<?, ?> result) {
                    toolCallId = result.get("id");
                }
                String key = toolCallId == null ? "unknown" : String.valueOf(toolCallId);
                if (state.finishedTools.add(key)) {
                    streams.add(
                            new AgentStream(
                                    AgentStream.Status.TOOL_FINISHED, "[工具执行完成]", seq));
                }
            }
            case "interaction/requested" -> {
                streams.add(
                        new AgentStream(
                                AgentStream.Status.PERMISSION_REQUESTED,
                                String.valueOf(event.data().get("requestId")),
                                seq));
                terminal = true;
            }
            case "turn/completed" -> {
                streams.add(
                        new AgentStream(AgentStream.Status.TURN_FINISHED, "completed", seq));
                terminal = true;
            }
            case "turn/suspended" -> terminal = true;
            case "turn/interrupted", "turn/cancelled" -> {
                streams.add(
                        new AgentStream(
                                AgentStream.Status.TURN_FINISHED, event.type(), seq));
                terminal = true;
            }
            case "turn/failed" -> {
                Object reason = event.data().get("reason");
                streams.add(
                        new AgentStream(
                                AgentStream.Status.ERROR,
                                reason == null ? "turn failed" : String.valueOf(reason),
                                seq));
                terminal = true;
            }
            default -> {
            }
        }
        return new Projection(event.seq(), streams, terminal);
    }

    private static void projectModelChunk(
            State state, SessionEvent event, List<AgentStream> streams) {
        Object rawChunk = event.data().get("chunk");
        if (!(rawChunk instanceof java.util.Map<?, ?> chunk)) {
            return;
        }
        Object rawContent = chunk.get("content");
        if (!(rawContent instanceof List<?> blocks)) {
            return;
        }
        for (Object value : blocks) {
            ContentBlock block = JsonUtils.getJsonCodec().convertValue(value, ContentBlock.class);
            if (block instanceof TextBlock text && !text.getText().isEmpty()) {
                streams.add(
                        new AgentStream(
                                AgentStream.Status.RESPONSE_STREAMING,
                                text.getText(),
                                event.seq()));
            } else if (block instanceof ThinkingBlock thinking
                    && thinking.getThinking() != null
                    && !thinking.getThinking().isEmpty()) {
                streams.add(
                        new AgentStream(
                                AgentStream.Status.THINKING_STREAMING,
                                thinking.getThinking(),
                                event.seq()));
            } else if (block instanceof ToolUseBlock tool) {
                announceTool(state, tool, event.seq(), streams);
            }
        }
    }

    private static void projectAssistantMessage(
            SessionEvent event, List<AgentStream> streams) {
        Object rawMessage = event.data().get("message");
        if (rawMessage == null) {
            return;
        }
        Msg message = JsonUtils.getJsonCodec().convertValue(rawMessage, Msg.class);
        String thinking =
                message.getContentBlocks(ThinkingBlock.class).stream()
                        .map(ThinkingBlock::getThinking)
                        .filter(text -> text != null && !text.isBlank())
                        .reduce("", String::concat);
        if (!thinking.isBlank()) {
            streams.add(
                    new AgentStream(
                            AgentStream.Status.THINKING_FINISHED, thinking, event.seq()));
        }
        String text = message.getTextContent();
        if (text != null && !text.isBlank()) {
            streams.add(
                    new AgentStream(
                            AgentStream.Status.RESPONSE_FINISHED, text, event.seq()));
        }
    }

    private static void projectToolRequested(
            State state, SessionEvent event, List<AgentStream> streams) {
        Object rawCall = event.data().get("call");
        if (rawCall == null) {
            return;
        }
        ToolUseBlock tool = JsonUtils.getJsonCodec().convertValue(rawCall, ToolUseBlock.class);
        announceTool(state, tool, event.seq(), streams);
    }

    private static void announceTool(
            State state, ToolUseBlock tool, long seq, List<AgentStream> streams) {
        String id = tool.getId();
        String name = tool.getName();
        if (name == null || name.isBlank()) {
            return;
        }
        String key = id == null || id.isBlank() ? name : id;
        if (state.announcedTools.add(key)) {
            streams.add(new AgentStream(AgentStream.Status.TOOL_STREAMING, name, seq));
        }
    }

    public record Projection(long seq, List<AgentStream> streams, boolean terminal) {
    }
}
