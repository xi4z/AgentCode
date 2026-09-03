package com.agentcode.factory;

import com.agentcode.agent.AgentContext;
import com.agentcode.memory.MemoryStore;
import com.agentcode.tools.MemorySearchTools;
import com.alibaba.cloud.ai.graph.agent.AgentTool;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;

import com.agentcode.tools.SessionNoteTools;
import com.alibaba.cloud.ai.graph.agent.extension.tools.filesystem.FileSystemTools;
import com.alibaba.cloud.ai.graph.agent.tools.GlobSearchTool;
import com.alibaba.cloud.ai.graph.agent.tools.GrepSearchTool;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
@RequiredArgsConstructor
public class AgentToolBuilder {

    private final ChatModel chatModel;

    /** 长期记忆库：memory_search 工具需要，故在 builder 层透传给 Builder。 */
    private final MemoryStore memoryStore;

    public Builder builder(AgentContext agentContext) {
        return new Builder(agentContext, memoryStore);
    }

    public static class Builder {

        private final String workspace;
        private final MemoryStore memoryStore;
        private final Map<String,ToolCallback> toolCallbacks = new LinkedHashMap<>(); // 使用 Linked 是因为保留顺序


        public Builder(AgentContext agentContext, MemoryStore memoryStore) {
            this.workspace = agentContext.getWorkspace();
            this.memoryStore = memoryStore;
        }

        public Builder mainAgent(){
            this.withSearchTools();
            this.withFileSystemTools();
            this.withSessionNotesTools();
            this.withMemoryTools();
            return this;
        }

        public Builder subAgent(){
            this.withSearchTools();
            this.withFileSystemToolsOnlyRead();
            return this;
        }

        public Builder withSubAgent(ReactAgent subAgent){
            ToolCallback toolCallback = AgentTool.create(subAgent);
            toolCallbacks.put(toolCallback.getToolDefinition().name(),toolCallback);
            return this;
        }

        public Builder withSearchTools(){
            ToolCallback grep = GrepSearchTool.builder(workspace).build();
            ToolCallback glob = GlobSearchTool.builder(workspace).build();
            toolCallbacks.put(grep.getToolDefinition().name(),grep);
            toolCallbacks.put(glob.getToolDefinition().name(),glob);
            return this;
        }

        public Builder withFileSystemToolsOnlyRead(){
            this.withFileSystemTools();
            toolCallbacks.remove("write_file");
            toolCallbacks.remove("edit_file");
            return this;
        }
        public Builder withFileSystemTools(){
            convertCallbacksToMap(
                    MethodToolCallbackProvider.builder()
                            .toolObjects(
                                    FileSystemTools.builder()
                                            .rootDir(workspace)
                                            .maxFileSizeMb(10)
                                            .build())
                            .build()
                            .getToolCallbacks());
            return this;
        }

        public Builder withSessionNotesTools(){
            convertCallbacksToMap(
                    MethodToolCallbackProvider.builder()
                            .toolObjects(
                                    new SessionNoteTools())
                            .build()
                            .getToolCallbacks());
            return this;
        }

        /**
         * 长期记忆查询工具（拉取式召回）。
         * 取代原先 MemoryHook.beforeAgent 的每轮主动注入：实测会把大半个记忆库灌进提示词。
         */
        public Builder withMemoryTools(){
            if (memoryStore == null) {
                return this;   // 未装配记忆库时静默不注册，不影响其它工具
            }
            convertCallbacksToMap(
                    MethodToolCallbackProvider.builder()
                            .toolObjects(new MemorySearchTools(memoryStore))
                            .build()
                            .getToolCallbacks());
            return this;
        }

        public List<ToolCallback> build() {
            return toolCallbacks.values().stream().toList();
        }

        private void convertCallbacksToMap(ToolCallback[] callbacks){
            for(ToolCallback toolCallback : callbacks){
                toolCallbacks.put(toolCallback.getToolDefinition().name(),toolCallback);
            }
        }
    }
}