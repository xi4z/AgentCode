# AgentCode 长期记忆（Java 分支）

跨会话长期记忆 = **模型自主维护的 markdown 文件**。没有向量库、没有置信度、没有后台抽取管线。

> 历史：第一版（本文档旧稿）是 Elasticsearch BM25 + dense_vector kNN + RRF 混排，配 afterAgent
> 抽取 Agent、向量去重（MATCH_THRESHOLD）、类型晋升（SESSION→PROJECT→GLOBAL→USER）、TTL、
> 命中强化等整套机器（单文件 1300+ 行）。实测库内 17 条记忆时主动召回会把大半个库灌进
> 提示词，指标失真；继续加阈值/重排/自愈是在给错误的架构打补丁。参照 Claude Code 的
> auto memory 与本仓库 Python 分支的 context.md 方案后整体换成现在的文件式设计：
> 代码量约为原来的 1/3，且原方案里的自我强化闭环（召回结果经 TOOL 消息回流抽取输入）、
> ES 故障面、embedding 标定依赖在结构上不复存在。

## 1. 存储布局

```text
~/.agent/memory/                  # 全局层（agentcode.agent.memory-dir）
├── MEMORY.md                     # 索引：每行一条摘要
├── user-package-manager.md       # 每条记忆一个文件
└── ...
<workspace>/.agent/memory/        # 项目层（固定路径，随项目走）
├── MEMORY.md
└── testing-conventions.md
```

- 索引行格式：`- [type] 摘要 → 文件名.md`
- 正文文件带 frontmatter：`type` / `name` / `summary` / `modified`（写入时间，ISO-8601）
- 全部纯 markdown，**人可以直接编辑**；手改索引/文件都兼容（frontmatter 缺失时摘要回退读索引行）
- 写入用 tmp + 原子 move；索引是 read-modify-write，工具可能并行调用（parallelToolExecution），实现内单锁

## 2. 四类记忆与两层作用域

| type | 含义 | 落点 |
|---|---|---|
| `user` | 用户身份、技能、长期偏好 | 全局层（跨项目） |
| `feedback` | 用户纠正/确认过的做法 | 全局层 |
| `project` | 无法从代码或 git 推出的项目事实、决定、约定 | 项目层 |
| `reference` | 外部资料在哪（工单、看板、文档位置） | 项目层 |

没有自动晋升：全局/项目由 type 决定，想跨层移动就是删了重写。

## 3. 生命周期

- **读（会话起点快照）**：`AgentSessionFactory.create()` 调 `FileMemoryStore.buildPromptBlock(workspace)`，
  把使用说明 + 两层索引拼进 system prompt。每层索引载入预算为 **200 行 / 25KB**（先到为准），
  超出部分不注入但标注条数，可用 memory_search 检索。
  刻意不做 beforeAgent 逐轮注入（旧主动召回的病根），会话中途新写入不回灌快照——
  模型刚写的内容本来就在它上下文里，其余新鲜度由工具实时读文件保证。
- **检索**：`memory_search(query)` 对两层的文件名/摘要/正文做关键词匹配
  （拉丁词 + CJK 连续串及其二元组），返回 top 8 条全文片段（单条截 1500 字、总预算 8000 字）。
- **写入**：`memory_write(type, name, summary, content)`，同名即覆盖更新（合并语义），自动维护索引。
- **遗忘**：`memory_forget(name, scope)`，删文件 + 清索引行；两层同名时必须指定 scope。

**修剪压力全部来自索引预算**：`memory_write` 在索引超过 200 行/25KB 时返回 ⚠ 提醒，
模型必须合并近似条目或删除过时记忆——这替代了旧方案用 confidence/TTL/晋升机器做的"软遗忘"。

## 4. 写入判据（交给模型，不靠管线）

工具描述与 `<auto_memory>` 块共同约束模型：

- 该写：用户表达长期偏好（"以后都用 pnpm"）、纠正/确认做法、给出项目约定或决定、
  出现以后会话仍需要且**无法从代码、git 历史、当前会话直接推出**的事实。
- 不该写：能从代码/文档查到的结构性事实、临时上下文、重复条目（应同名覆盖合并）。
- "remember X" 类显式指令直接落库。

## 5. 配置

```yaml
agentcode:
  agent:
    memory-dir: ~/.agent/memory   # env: AGENT_MEMORY_DIR；只影响全局层
```

`spring.ai.model.embedding` 默认已改为 `none`（不再有 EmbeddingModel 消费者；
pom 中 elasticsearch-java 依赖与 spring.elasticsearch 配置同步移除）。

## 6. 实现与审计

- 接口：`com.agentcode.memory.MemoryStore`（buildPromptBlock / search / write / forget，全部返回
  给模型看的文本，**实现禁止抛异常**）
- 实现：`com.agentcode.memory.FileMemoryStore`
- 工具：`com.agentcode.tools.MemoryTools`（memory_search / memory_write / memory_forget，
  workspace 经 `SessionConfigKeys.AGENT_CONTEXT` 从 ToolContext 取）
- 审计事件（单行、可 grep）：`AUDIT_MEMORY_WRITE` / `AUDIT_MEMORY_FORGET` /
  `AUDIT_MEMORY_TOOL_SEARCH` / `AUDIT_MEMORY_TOOL_FAILED` / `AUDIT_MEMORY_PROMPT_FAILED`
- fail-soft：长期记忆任何故障只表现为一条降级文本，不允许影响一轮 run

## 7. 什么时候才该回到向量方案

当前判断依据：索引注入 + 关键词检索的量级上限大约几百条记忆；索引预算逼着库保持小而干净。
只有当"词面不匹配导致的漏召回"成为实测主要失败模式、且合并修剪已到极限时，
才值得在 search 这一条腿上重新引入 embedding（存储与写入判据不需要动）。
