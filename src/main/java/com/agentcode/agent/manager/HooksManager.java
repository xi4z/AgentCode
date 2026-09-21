package com.agentcode.agent.manager;

import com.alibaba.cloud.ai.graph.agent.hook.Hook;
import com.alibaba.cloud.ai.graph.agent.hook.hip.HumanInTheLoopHook;
import com.alibaba.cloud.ai.graph.agent.hook.hip.ToolConfig;
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

/**
 * ReactAgent 的 Hook 装配。
 *
 * 与 ToolManager / InterceptorManager 同一套写法：Builder 逐个登记，
 * build() 交出可直接传给 {@code ReactAgent.builder().hooks(...)} 的列表。
 */
public class HooksManager {

    private HooksManager() {
    }

    public static Builder builder(ChatModel chatModel, String workspace) {
        return new Builder(chatModel, workspace);
    }

    @RequiredArgsConstructor
    public static class Builder {
        private final ChatModel chatModel;
        private final String workspace;
        private final List<Hook> hooks = new ArrayList<>();

        /** shell Hooks, 在审批前后防止 Shell 会话中断 */
        public Builder shell(ShellTool2 tool) {
            hooks.add(ShellToolAgentHook.builder().shellTool2(tool).shellToolName("shell").build());
            return this;
        }

        /** Token 成本控制 */
        public Builder summarization() {
            hooks.add(
                    SummarizationHook.builder()
                            .model(chatModel)
                            .maxTokensBeforeSummary(4000)
                            .messagesToKeep(20).build()
            );
            return this;
        }

        /** 调用控制 */
        public Builder callLimit() {
            hooks.add(ModelCallLimitHook.builder().runLimit(10).build());
            return this;
        }

        /** Skill 侧控制 */
        public Builder skill() {
            hooks.add(
                    SkillsAgentHook.builder().skillRegistry(FileSystemSkillRegistry.builder()
                            .projectSkillsDirectory(workspace + "/skills")
                            .build()).build()
            );
            return this;
        }

        /** 需要人工审批的工具，通过 HumanInTheLoopHook 在调用前中断；空列表表示全部免审批 */
        public Builder approval(List<String> tools) {
            if (tools == null || tools.isEmpty()) {
                return this;
            }
            HumanInTheLoopHook.Builder hitlBuilder = HumanInTheLoopHook.builder();
            for (String tool : tools) {
                hitlBuilder.approvalOn(tool, ToolConfig.builder()
                        .description("该工具调用需要人工审批")
                        .build());
            }
            hooks.add(hitlBuilder.build());
            return this;
        }

        public List<Hook> build() {
            return List.copyOf(hooks);
        }
    }
}
