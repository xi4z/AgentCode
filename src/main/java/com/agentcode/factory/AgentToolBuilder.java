package com.agentcode.factory;

import com.agentcode.agent.AgentContext;
import com.agentcode.memory.MemoryStore;
import com.agentcode.tools.MemoryTools;
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

    /** 长期记忆库（文件式）：memory_search / memory_write / memory_forget 工具需要，透传给 Builder。 */
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
         * 长期记忆工具组（文件式拉取召回 + 模型自主写入/遗忘）。
         * 取代旧链路：beforeAgent 每轮主动注入 → 实测把大半个库灌进提示词；
         * afterAgent 后台抽取 + ES 向量去重 → 复杂度不成比例。现在记忆 = markdown 文件，
         * 索引随会话起点注入，全文检索与增删都由模型调用这三个工具完成。
         */
        public Builder withMemoryTools(){
            if (memoryStore == null) {
                return this;   // 未装配记忆库时静默不注册，不影响其它工具
            }
            convertCallbacksToMap(
                    MethodToolCallbackProvider.builder()
                            .toolObjects(new MemoryTools(memoryStore))
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