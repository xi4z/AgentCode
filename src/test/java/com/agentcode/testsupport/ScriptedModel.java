package com.agentcode.testsupport;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.util.JsonUtils;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 确定性的离线模型：根据用户输入决定直接回答、读文件或执行 shell。
 */
public final class ScriptedModel implements Model {

    private final List<String> userInputs = new CopyOnWriteArrayList<>();

    public List<String> userInputs() {
        return List.copyOf(userInputs);
    }

    @Override
    public String getModelName() {
        return "scripted";
    }

    @Override
    public Flux<ChatResponse> stream(
            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        Msg last = messages.get(messages.size() - 1);
        if (last.hasContentBlocks(ToolResultBlock.class)) {
            return text("工具执行完成。");
        }

        Msg user = lastUser(messages);
        String input = user == null ? "" : user.getTextContent();
        if (!input.isBlank()) {
            userInputs.add(input);
        }
        if (input.contains("读取")) {
            return toolCall("read_file", Map.of("path", "hello.txt"));
        }
        if (input.contains("shell") || input.contains("命令")) {
            return toolCall("shell", Map.of("command", "mkdir approved_dir"));
        }
        return text("已收到: " + input);
    }

    private Msg lastUser(List<Msg> messages) {
        for (int index = messages.size() - 1; index >= 0; index--) {
            Msg message = messages.get(index);
            if (message.getRole() == MsgRole.USER) {
                return message;
            }
        }
        return null;
    }

    private Flux<ChatResponse> text(String reply) {
        String responseId = UUID.randomUUID().toString();
        return Flux.just(reply.substring(0, Math.min(3, reply.length())), reply.substring(Math.min(3, reply.length())))
                .filter(part -> !part.isEmpty())
                .map(
                        part ->
                                ChatResponse.builder()
                                        .id(responseId)
                                        .content(List.of(TextBlock.builder().text(part).build()))
                                        .build());
    }

    private Flux<ChatResponse> toolCall(String name, Map<String, Object> input) {
        ToolUseBlock call =
                ToolUseBlock.builder()
                        .id(UUID.randomUUID().toString())
                        .name(name)
                        .input(input)
                        .content(JsonUtils.getJsonCodec().toJson(input))
                        .build();
        return Flux.just(
                ChatResponse.builder()
                        .id(UUID.randomUUID().toString())
                        .content(List.of(call))
                        .finishReason("tool_calls")
                        .build());
    }
}
