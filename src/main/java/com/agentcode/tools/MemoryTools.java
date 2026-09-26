package com.agentcode.tools;

import com.agentcode.agent.context.AgentContext;
import com.agentcode.memory.MemoryStore;
import com.agentcode.session.SessionEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 长期记忆的模型侧入口：检索 / 写入 / 遗忘。
 *
 * <p>权限边界由 {@link MemoryStore} 定：写入与删除只作用于工作区的 {@code .memory/memory.md}；
 * 全局记忆（服务配置）与工作区 {@code Agent.md} 都是只读层，这里的工具碰不到它们。
 *
 * <p>workspace 从 ToolContext 里的 {@link com.agentcode.agent.context.AgentContext} 取，
 * 与 {@link SessionNoteTools} 同一约定；所有操作 fail-soft，记忆故障只体现为一条降级文本。
 */
@Slf4j
public class MemoryTools {

    private final MemoryStore memoryStore;

    public MemoryTools(MemoryStore memoryStore) {
        this.memoryStore = memoryStore;
    }

    @Tool(name = "memory_search",
            description = "按关键词检索跨会话长期记忆（全局记忆、工作区 Agent.md、你之前记下的 .memory/memory.md），"
                    + "返回命中片段的全文。当回答依赖「用户以前说过什么 / 项目既有约定 / 你之前记过什么」时使用。"
                    + "查不到就直接回答不知道，不要编造记忆。")
    public String searchMemory(
            @ToolParam(description = "关键词或自然语言查询，如“包管理器 pnpm”") String query,
            ToolContext toolContext) {
        try {
            return memoryStore.search(workspace(toolContext), query);
        } catch (Exception e) {
            log.warn("AUDIT_MEMORY_TOOL_FAILED tool=memory_search runId={} error={}", runId(toolContext), e.getMessage());
            return "长期记忆查询暂时不可用，请按「没有相关记忆」处理，不要编造。";
        }
    }

    @Tool(name = "memory_write",
            description = "保存或按同名覆盖一条长期记忆，落到工作区的 .memory/memory.md，同一工作区的新会话开局即可见。"
                    + "使用时机：用户表达长期偏好（如「以后都用 pnpm」）、纠正或确认你的做法、给出项目约定，"
                    + "或出现以后会话仍然需要的事实时。只记结论本身，一到三句；"
                    + "与已有记忆重复时用同名覆盖合并，不要堆近似条目；能从代码或 git 历史直接看出的内容不要记。"
                    + "全局记忆与 Agent.md 是只读的，改不了，也不要用这两个工具去「同步」它们。")
    public String writeMemory(
            @ToolParam(description = "简短名字（小写字母/数字/连字符或中文，如 pnpm-preference；同名即覆盖更新）") String name,
            @ToolParam(description = "一句话摘要") String summary,
            @ToolParam(description = "记忆正文，面向未来会话的简明事实") String content,
            ToolContext toolContext) {
        try {
            return memoryStore.write(workspace(toolContext), name, summary, content);
        } catch (Exception e) {
            log.warn("AUDIT_MEMORY_TOOL_FAILED tool=memory_write runId={} error={}", runId(toolContext), e.getMessage());
            return "长期记忆写入失败，本轮按未保存处理；不要反复重试。";
        }
    }

    @Tool(name = "memory_forget",
            description = "删除一条长期记忆：用户否认旧偏好、约定作废、或记忆过时。"
                    + "name 传 memory_write 时用的名字。只能删 .memory/memory.md 里的条目，删除不可恢复。")
    public String forgetMemory(
            @ToolParam(description = "要删除的记忆名，如 pnpm-preference") String name,
            ToolContext toolContext) {
        try {
            return memoryStore.forget(workspace(toolContext), name);
        } catch (Exception e) {
            log.warn("AUDIT_MEMORY_TOOL_FAILED tool=memory_forget runId={} error={}", runId(toolContext), e.getMessage());
            return "长期记忆删除失败，本轮按未删除处理。";
        }
    }

    private String workspace(ToolContext toolContext) {
        AgentContext agentContext = agentContext(toolContext);
        return agentContext == null ? null : agentContext.getWorkspace();
    }

    private AgentContext agentContext(ToolContext toolContext) {
        if (toolContext == null || toolContext.getContext() == null) {
            return null;
        }
        Object context = toolContext.getContext().get(SessionEnum.AGENT_CONTEXT.getCode());
        return context instanceof AgentContext agentContext ? agentContext : null;
    }

    private String runId(ToolContext toolContext) {
        AgentContext agentContext = agentContext(toolContext);
        return agentContext == null || agentContext.getRunId() == null ? "-" : agentContext.getRunId();
    }
}
