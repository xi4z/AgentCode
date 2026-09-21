package com.agentcode.agent.manager;

import com.alibaba.cloud.ai.graph.agent.hook.Hook;
import com.alibaba.cloud.ai.graph.agent.hook.modelcalllimit.ModelCallLimitHook;
import com.alibaba.cloud.ai.graph.agent.hook.shelltool.ShellToolAgentHook;
import com.alibaba.cloud.ai.graph.agent.hook.skills.SkillsAgentHook;
import com.alibaba.cloud.ai.graph.agent.hook.summarization.SummarizationHook;
import com.alibaba.cloud.ai.graph.agent.tools.ShellTool2;
import com.alibaba.cloud.ai.graph.skills.registry.filesystem.FileSystemSkillRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ChatModel;

import java.util.ArrayList;
import java.util.List;

public class HooksManager {
    private List<Hook> hooks;
    private HooksManager(List<Hook> hooks) {
        this.hooks = hooks;
    }
    @RequiredArgsConstructor
    public class Builder {
        private List<Hook> hooks = new ArrayList<>();
        private final ChatModel chatModel;
        private final String workspace;
        Builder summarization(){
            hooks.add(
                    SummarizationHook.builder()
                            .model(chatModel)
                            .maxTokensBeforeSummary(4000)
                            .messagesToKeep(20).build()
            );
            return this;
        }

        Builder skill(){
            hooks.add(
                    SkillsAgentHook.builder().skillRegistry(FileSystemSkillRegistry.builder()
                            .projectSkillsDirectory(workspace + "/skills")
                            .build()).build() // Skill 侧控制
            );
            return this;
        }

        Builder callLimit(){
            hooks.add(ModelCallLimitHook.builder().runLimit(10).build());
            return this;
        }

        Builder shell(ShellTool2 tool){
            hooks.add(ShellToolAgentHook.builder().shellTool2(tool).shellToolName("shell").build());
            return this;
        }

        HooksManager build(){
            return new HooksManager(hooks);
        }
    }
}
