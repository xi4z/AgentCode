package com.agentcode.agent.manager;

import com.agentcode.agent.tools.SessionNoteTools;
import com.agentcode.memory.MemoryStore;
import com.agentcode.tools.MemoryTools;
import com.alibaba.cloud.ai.graph.agent.AgentTool;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.extension.tools.filesystem.FileSystemTools;
import com.alibaba.cloud.ai.graph.agent.tools.GlobSearchTool;
import com.alibaba.cloud.ai.graph.agent.tools.GrepSearchTool;

import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ToolManager {

    private ToolManager() {
    }

    public static Builder builder(String workspace) {
        return new Builder(workspace, null);
    }

    /** memoryStore 为 null = 长期记忆关闭，不注册记忆工具 */
    public static Builder builder(String workspace, MemoryStore memoryStore) {
        return new Builder(workspace, memoryStore);
    }

    @RequiredArgsConstructor
    public static class Builder {

        private final String workspace;

        /** 长期记忆库（文件式）：memory_search / memory_write / memory_forget 工具需要，已随 withMemoryTools 一起停用。 */
        private final MemoryStore memoryStore;
        private final Map<String, ToolCallback> toolCallbacks = new LinkedHashMap<>(); // 使用 Linked 是因为保留顺序


        public Builder mainAgent() {
            this.withSearchTools();
            this.withFileSystemTools();
            this.withSessionNotesTools();
            this.withMemoryTools();
            return this;
        }

        public Builder subAgent() {
            this.withSearchTools();
            this.withFileSystemToolsOnlyRead();
            return this;
        }

        public Builder withSubAgent(ReactAgent subAgent) {
            ToolCallback toolCallback = AgentTool.create(subAgent);
            toolCallbacks.put(toolCallback.getToolDefinition().name(), toolCallback);
            return this;
        }

        public Builder withSearchTools() {
            ToolCallback grep = GrepSearchTool.builder(workspace).build();
            ToolCallback glob = GlobSearchTool.builder(workspace).build();
            toolCallbacks.put(grep.getToolDefinition().name(), grep);
            toolCallbacks.put(glob.getToolDefinition().name(), glob);
            return this;
        }

        public Builder withFileSystemToolsOnlyRead() {
            this.withFileSystemTools();
            toolCallbacks.remove("write_file");
            toolCallbacks.remove("edit_file");
            return this;
        }

        public Builder withFileSystemTools() {
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

        public Builder withSessionNotesTools() {
            convertCallbacksToMap(
                    MethodToolCallbackProvider.builder()
                            .toolObjects(
                                    new SessionNoteTools())
                            .build()
                            .getToolCallbacks());
            return this;
        }

        /**
         * 长期记忆工具组：memory_search / memory_write / memory_forget。
         *
         * <p>只在装配了记忆库（{@code agentcode.memory.enabled=true}）时注册；写入只作用于
         * 工作区 {@code .memory/memory.md}，全局记忆与 Agent.md 是只读层。
         * 注意这三个工具不挂审批门禁 —— 它们只能动 agent 自己那份记忆文件，路径由服务端固定，
         * 模型给不出路径，越界写不出去（见 FileMemoryStore）。
         */
        public Builder withMemoryTools() {
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

        private void convertCallbacksToMap(ToolCallback[] callbacks) {
            for (ToolCallback toolCallback : callbacks) {
                toolCallbacks.put(toolCallback.getToolDefinition().name(), toolCallback);
            }
        }
    }
}

