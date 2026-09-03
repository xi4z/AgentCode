package com.agentcode.factory;

import com.agentcode.agent.AgentContext;
import com.agentcode.hooks.CheckpointAgentMetricsHook;
import com.agentcode.hooks.CheckpointModelMetricsHook;
import com.agentcode.hooks.MemoryHook;
import com.agentcode.hooks.UpdateSessionNotesHook;
import com.agentcode.memory.MemoryStore;
import com.agentcode.properties.AgentCodeProperties;
import com.agentcode.store.AgentContextStore;
import com.alibaba.cloud.ai.graph.agent.hook.Hook;
import com.alibaba.cloud.ai.graph.agent.hook.hip.HumanInTheLoopHook;
import com.alibaba.cloud.ai.graph.agent.hook.hip.ToolConfig;
import com.alibaba.cloud.ai.graph.agent.hook.modelcalllimit.ModelCallLimitHook;
import com.alibaba.cloud.ai.graph.agent.hook.shelltool.ShellToolAgentHook;
import com.alibaba.cloud.ai.graph.agent.hook.skills.SkillsAgentHook;
import com.alibaba.cloud.ai.graph.agent.hook.summarization.SummarizationHook;
import com.alibaba.cloud.ai.graph.agent.tools.ShellTool2;
import com.alibaba.cloud.ai.graph.skills.registry.filesystem.FileSystemSkillRegistry;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
public class AgentHookBuilder {

    private final ChatModel chatModel;
    private final AgentCodeProperties properties;
    private final AgentContextStore agentContextStore;
    private final MemoryStore memoryStore;

    public Builder builder(AgentContext agentContext) {
        return new Builder(chatModel, properties, agentContext, agentContextStore, memoryStore);
    }

    public static class Builder {

        private final ChatModel chatModel;
        private final AgentCodeProperties properties;
        private final AgentContextStore store;
        private final String workspace;
        private final List<Hook> hooks = new ArrayList<>();
        private ShellTool2 shellTool2;
        private final MemoryStore memoryStore;

        private Builder(ChatModel chatModel, AgentCodeProperties properties, AgentContext agentContext, AgentContextStore store, MemoryStore memoryStore) {
            this.chatModel = chatModel;
            this.properties = properties;
            this.store = store;
            this.workspace = agentContext.getWorkspace();
            this.memoryStore = memoryStore;
        }


        public Builder withShellTool() {
            if (shellTool2 == null) {
                shellTool2 = ShellTool2.builder(workspace).build();
            }
            hooks.add(ShellToolAgentHook.builder()
                    .shellTool2(shellTool2)
                    .shellToolName("shell")
                    .build());
            return this;
        }

        public Builder withSummarization() {
            return withSummarization(properties.getAgent().getMaxTokensBeforeSummary(),  properties.getAgent().getMessagesToKeep());
        }

        public Builder withSummarization(int maxTokensBeforeSummary, int messagesToKeep) {
            hooks.add(SummarizationHook.builder()
                    .model(chatModel)
                    .maxTokensBeforeSummary(maxTokensBeforeSummary)
                    .messagesToKeep(messagesToKeep)
                    .build());
            return this;
        }


        // 模型调用上线的 Hooks, 不加参数时默认为最大的 maxSteps
        public Builder withModelCallLimit() {
            return this.withModelCallLimit(properties.getAgent().getMaxSteps());
        }
        public Builder withModelCallLimit(int maxSteps) {
            hooks.add(ModelCallLimitHook.builder()
                    .runLimit(maxSteps)
                    .build());
            return this;
        }

        public Builder withSkills() {
            hooks.add(SkillsAgentHook.builder()
                    .skillRegistry(FileSystemSkillRegistry.builder()
                            .projectSkillsDirectory(workspace + "/skills")
                            .build())
                    .build());
            return this;
        }

        public Builder withApproval(List<String> approvalTools) {
            if (approvalTools == null || approvalTools.isEmpty()) {
                return this;
            }
            HumanInTheLoopHook.Builder hitlBuilder = HumanInTheLoopHook.builder();
            for (String tool : approvalTools) {
                hitlBuilder.approvalOn(tool, ToolConfig.builder()
                        .description("该工具调用需要人工审批")
                        .build());
            }
            hooks.add(hitlBuilder.build());
            return this;
        }

        /**
         * 挂载长期记忆 Hook（<b>只负责写入侧</b>）。
         *
         * <p>beforeAgent 仅记录「本轮记忆起点」（最后一条 user 消息下标），不做召回；回忆改由模型
         * 按需调用 {@code memory_search} 工具完成（见 {@link com.agentcode.tools.MemorySearchTools}）——
         * 原先每轮主动注入实测会把大半个记忆库灌进提示词。afterAgent 的记忆抽取落库在记忆专用线程池
         * 异步执行，本方法与 hook 回调均不阻塞主链路。
         */
        public Builder withMemory(){
            MemoryHook memoryHook = new MemoryHook(memoryStore);
            hooks.add(memoryHook);
            return this;
        }

        public Builder withUpdateSessionNotes(){
            UpdateSessionNotesHook updateSessionNotesHook = new UpdateSessionNotesHook(store);
            hooks.add(updateSessionNotesHook);
            return this;
        }

        public Builder withCheckpointMetrics() {
            hooks.add(new CheckpointModelMetricsHook());
            hooks.add(new CheckpointAgentMetricsHook());
            return this;
        }



        public Result build() {
            return new Result(List.copyOf(hooks), shellTool2);
        }
    }

    @AllArgsConstructor
    @Data
    public static class Result{
        private List<Hook> hooks;
        private ShellTool2 shellTool2;
    }
}