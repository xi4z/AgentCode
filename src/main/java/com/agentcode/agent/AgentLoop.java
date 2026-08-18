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
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.alibaba.cloud.ai.graph.streaming.OutputType;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@Component
@RequiredArgsConstructor
public class AgentLoop {
    private final ChatModel chatModel;
    private final MemorySaver memorySaver;
    @Getter
    private final ConcurrentHashMap<String, ReactAgent> reactAgentMap = new ConcurrentHashMap<>();


    public Flux<AgentStream> run(AgentContext context) throws GraphRunnerException {
        // TODO 可以根据设定注入可用工具
        String workspace = resolveWorkspace(context);
        ShellTool2 shellTool2 = ShellTool2.builder(workspace).build();
        FileSystemTools fst = FileSystemTools.builder()
                .rootDir(workspace)
                .maxFileSizeMb(10)
                .build();

        ReactAgent reactAgent = ReactAgent.builder()
                .name("minimal_agent")
                .model(chatModel)
                .saver(memorySaver)
                .tools(defaultTools(workspace))
                .methodTools(fst)
                .hooks(ShellToolAgentHook.builder()
                        .shellTool2(shellTool2)
                        .shellToolName("shell")
                        .build())
                .systemPrompt(context.systemPrompt())
                .build();

        // 在重新 run 之后, 修改 context 状态
        RunnableConfig config = RunnableConfig.builder()
                .threadId(context.getRunId()) // 获取数据
                .build();

        reactAgentMap.put(context.getRunId(), reactAgent);
        return reactAgent.stream(context.getGoal(), config).concatMap(this::classifyMessage).doFinally(signalType -> {
                    reactAgentMap.remove(context.getRunId()); // 移除, 防止溢出
                }
        );
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

    private List<ToolCallback> defaultTools(String workspace) {
        ToolCallback grep = GrepSearchTool.builder(workspace).build();
        ToolCallback glob = GlobSearchTool.builder(workspace).build();


        List<ToolCallback> tools = new ArrayList<>();
        tools.add(grep);
        tools.add(glob);
        return tools;
    }

    private String resolveWorkspace(AgentContext agentContext) {
        String workspace = agentContext.getWorkspace();
        if (workspace == null || workspace.isBlank()) {
            return System.getProperty("user.dir");
        }
        return workspace;
    }
}
