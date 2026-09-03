package com.agentcode.memory;

/**
 * 跨会话长期记忆（文件式存储，对齐 Claude Code auto memory / 本仓库 Python 分支 context.md）。
 *
 * <p>每条记忆是一个 markdown 文件（frontmatter 存 type/name/summary/modified），每个记忆目录用
 * {@code MEMORY.md} 做单行索引（格式 {@code - [type] 摘要 → 文件名.md}）。检索 = 对
 * 文件名/摘要/正文的关键词匹配。<b>没有</b>向量索引、置信度、命中强化、类型晋升、TTL——
 * 修剪靠索引硬预算（200 行 / 25KB，先到为准）逼模型合并，而不是靠打分。
 *
 * <p>两层作用域：
 * <ul>
 *   <li>全局层：{@code <agentcode.agent.memory-dir>}（默认 {@code ~/.agent/memory}），
 *       存 user / feedback 类记忆（用户本人跨项目都成立的事实）；</li>
 *   <li>项目层：{@code <workspace>/.agent/memory/}，存 project / reference 类记忆。</li>
 * </ul>
 *
 * <p>生命周期（刻意与旧 ES 版不同）：
 * <ul>
 *   <li>读：会话创建时由 AgentSessionFactory 调用 {@link #buildPromptBlock(String)} 把两层索引
 *       拼进 system prompt——只在会话起点注入一次快照，不做 beforeAgent 逐轮注入（旧主动召回
 *       实测会把大半个库灌进提示词）；</li>
 *   <li>检索/写入/遗忘：全部由模型在会话内通过 memory_search / memory_write / memory_forget
 *       工具触发（见 {@link com.agentcode.tools.MemoryTools}）。没有后台抽取管线，
 *       「什么值得记」从「每轮跑一次抽取 Agent」改为「模型在当下判断」。</li>
 * </ul>
 *
 * <p>实现契约：任何方法都不得向调用方抛异常——长期记忆的故障不允许影响一轮 run，
 * 异常一律降级为给模型看的说明文本并留 AUDIT_MEMORY_* 审计行。并发与原子性由实现负责，
 * 见 {@link FileMemoryStore}。
 */
public interface MemoryStore {

    /**
     * 拼进 system prompt 的 {@code <auto_memory>} 块：使用说明 + 两层索引（各自按预算截断）。
     * 仅读取失败时返回空串（调用方跳过拼接）。
     */
    String buildPromptBlock(String workspace);

    /** 关键词检索两层记忆的索引与正文，返回给模型看的格式化文本；无命中返回固定提示语。 */
    String search(String workspace, String query);

    /**
     * 写入或按同名覆盖一条记忆（自动维护所在层的索引）。
     *
     * @param type user / feedback（全局层）或 project / reference（项目层），大小写不敏感
     */
    String write(String workspace, String type, String name, String summary, String content);

    /**
     * 按名称删除一条记忆（自动清理索引行）。
     *
     * @param scope global / project 限定层；空 = 两层都找，同名冲突时返回要求指明的提示
     */
    String forget(String workspace, String scope, String name);
}
