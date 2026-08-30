package com.agentcode.factory;

import com.agentcode.agent.AgentContext;
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


    public Builder builder(AgentContext agentContext) {
        return new Builder(agentContext);
    }

    public static class Builder {

        private final String workspace;
        private final Map<String,ToolCallback> toolCallbacks = new LinkedHashMap<>(); // 使用 Linked 是因为保留顺序


        public Builder(AgentContext agentContext) {
            this.workspace = agentContext.getWorkspace();
        }

        public Builder mainAgent(){
            this.withSearchTools();
            this.withFileSystemTools();
            this.withSessionNotesTools();
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