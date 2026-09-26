package com.agentcode.memory;

/**
 * 跨会话长期记忆，三层：
 *
 * <ol>
 *   <li><b>全局记忆</b>：来自服务的核心配置（{@code agentcode.memory.global}），**只读**，
 *       agent 不能改，只有人能改（改配置/重启）；</li>
 *   <li><b>工作区 Agent.md</b>：{@code <workspace>/Agent.md}，**只读**，人写项目约定，agent 只读不改；</li>
 *   <li><b>工作区 .memory/memory.md</b>：**可写**，agent 通过 memory_write / memory_forget 自由增删改，
 *       跨会话保留（同一工作区的新会话开局就能看到）。</li>
 * </ol>
 *
 * <p>契约：任何方法都不得向调用方抛异常 —— 长期记忆的故障不允许影响一轮 run，
 * 异常一律降级为给模型看的说明文本。
 */
public interface MemoryStore {

    /** 拼进 system prompt 的记忆块：三层内容 + 使用说明。仅在某层读不到时跳过该层。 */
    String buildPromptBlock(String workspace);

    /** 关键词检索三层记忆，返回给模型看的格式化文本；无命中返回固定提示语。 */
    String search(String workspace, String query);

    /** 写入或按同名覆盖一条记忆（只落 {@code .memory/memory.md}）。 */
    String write(String workspace, String name, String summary, String content);

    /** 按名称删除一条记忆（只动 {@code .memory/memory.md}）。 */
    String forget(String workspace, String name);

    /** agent 可写的那份记忆文件（绝对路径）；给测试与运维看。 */
    String writableMemoryPath(String workspace);
}
