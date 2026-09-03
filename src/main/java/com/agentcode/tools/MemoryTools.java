package com.agentcode.tools;

import com.agentcode.agent.AgentContext;
import com.agentcode.common.SessionConfigKeys;
import com.agentcode.memory.MemoryStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 长期记忆的模型侧入口：检索 / 写入 / 遗忘三个工具。
 *
 * <p>取代两代旧设计：① MemoryHook.beforeAgent 的每轮主动注入（实测把大半个库灌进提示词）；
 * ② afterAgent 的后台抽取 Agent + ES 向量去重管线（复杂度与自我强化闭环都不成比例）。
 * 现在「什么值得记、要不要回忆」都由模型在会话内用这些工具当场决定，
 * 存储是 markdown 文件（见 com.agentcode.memory.FileMemoryStore），全部操作 fail-soft，
 * 长期记忆故障只体现为一条降级文本，绝不炸掉本轮 run。
 *
 * <p>workspace 通过 {@link SessionConfigKeys#AGENT_CONTEXT} 从 ToolContext 取，
 * 与 {@link SessionNoteTools} 同一约定；这里用 instanceof 防御而非直接强转。
 */
@Slf4j
public class MemoryTools {

    private final MemoryStore memoryStore;

    public MemoryTools(MemoryStore memoryStore) {
        this.memoryStore = memoryStore;
    }

    @Tool(name = "memory_search",
            description = "按关键词检索跨会话长期记忆（用户偏好、用户对做法的反馈、项目约定、外部资料位置），"
                    + "返回命中条目的全文片段。当回答依赖「用户以前说过什么 / 项目既有约定」，"
                    + "或系统提示中的记忆索引被截断、需要某条记忆的全文时使用。"
                    + "查不到结果就直接回答不知道，不要编造记忆。")
    public String searchMemory(
            @ToolParam(description = "关键词或自然语言查询，如“包管理器 pnpm”") String query,
            ToolContext toolContext) {
        try {
            return memoryStore.search(workspace(toolContext), query);
        } catch (Exception e) {
            log.warn("AUDIT_MEMORY_TOOL_FAILED tool=memory_search runId={} error={}", runId(toolContext), e.getMessage(), e);
            return "长期记忆查询暂时不可用，请按「没有相关记忆」处理，不要编造。";
        }
    }

    @Tool(name = "memory_write",
            description = "保存或按同名覆盖一条长期记忆（自动维护 MEMORY.md 索引与更新时间）。"
                    + "使用时机：用户表达长期偏好（如“以后都用 pnpm”）、纠正或确认你的做法、"
                    + "给出项目约定/决定，或出现以后会话仍然需要的事实。"
                    + "type：user=用户身份/偏好，feedback=用户的纠正与确认（这两种存全局、跨项目）；"
                    + "project=本项目无法从代码或 git 推出的事实/约定，reference=外部资料在哪（这两种存当前项目）。"
                    + "只记结论本身，一到三句；与已有记忆重复时用同名覆盖合并，不要新增近似条目。"
                    + "可从代码库、当前会话直接看出的内容不要记。")
    public String writeMemory(
            @ToolParam(description = "记忆类型：user | feedback | project | reference") String type,
            @ToolParam(description = "简短英文文件名（小写、连字符，如 pnpm-preference；同名即覆盖更新）") String name,
            @ToolParam(description = "一句话摘要，会写入索引") String summary,
            @ToolParam(description = "记忆正文，面向未来会话的简明事实") String content,
            ToolContext toolContext) {
        try {
            return memoryStore.write(workspace(toolContext), type, name, summary, content);
        } catch (Exception e) {
            log.warn("AUDIT_MEMORY_TOOL_FAILED tool=memory_write runId={} error={}", runId(toolContext), e.getMessage(), e);
            return "长期记忆写入失败，本轮按未保存处理；不要反复重试，也不要把失败当作用户信息。";
        }
    }

    @Tool(name = "memory_forget",
            description = "删除一条长期记忆：用户否认旧偏好、约定作废、或索引超出 200 行/25KB 预算需要清理时使用。"
                    + "name 传索引行末尾的文件名（.md 可省略）；scope 留空自动在两层查找，"
                    + "两层同名时必须指定 global 或 project。删除不可恢复。")
    public String forgetMemory(
            @ToolParam(description = "要删除的记忆文件名，如 pnpm-preference") String name,
            @ToolParam(description = "限定层：global 或 project；留空自动查找") String scope,
            ToolContext toolContext) {
        try {
            return memoryStore.forget(workspace(toolContext), scope, name);
        } catch (Exception e) {
            log.warn("AUDIT_MEMORY_TOOL_FAILED tool=memory_forget runId={} error={}", runId(toolContext), e.getMessage(), e);
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
        Object context = toolContext.getContext().get(SessionConfigKeys.AGENT_CONTEXT);
        return context instanceof AgentContext agentContext ? agentContext : null;
    }

    private String runId(ToolContext toolContext) {
        AgentContext agentContext = agentContext(toolContext);
        return agentContext == null || agentContext.getRunId() == null ? "-" : agentContext.getRunId();
    }
}
