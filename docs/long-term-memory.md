# AgentCode 长期记忆策略

本文记录当前 `com.agentcode.memory` 长期记忆模块的设计策略和已实现规则。当前实现主要参考：

- Mem0：先抽取/判断是否值得记忆，再决定新增、合并或更新
- Letta / MemGPT：分层记忆，工作记忆与长期记忆分离
- 腾讯云 Agent Memory：临时上下文、工作记忆、长期记忆分层
- Zep / Graphiti：情景记忆、实体关系记忆（第一版先预留扩展点）
- Elasticsearch 8.x：BM25 倒排检索 + dense_vector kNN + RRF 原生混合检索

## 1. 记忆类型

当前使用四类记忆：

```text
SESSION：会话级，默认类型，只在当前会话中最有价值
PROJECT：项目级，属于某个项目或仓库
GLOBAL：全局级，跨项目通用经验
USER：用户级，长期用户偏好
```

升级路径：

```text
SESSION -> PROJECT -> GLOBAL -> USER
```

## 2. 记忆模型

`MemoryRecord` 字段：

```text
memoryId：唯一 ID，同时作为 Elasticsearch 文档 _id
type：记忆类型，SESSION / PROJECT / GLOBAL / USER
content：记忆正文
confidence：置信度，0~1
updateAt：最近一次更新时间
ttl：有效时长，单位秒，从 updateAt 起算
hitCount：累计命中次数
meta：扩展元数据，例如 runId、projectId、sessionId、hitRunIds 等
```

## 3. 默认 TTL

当前默认 TTL：

```text
SESSION：1 天
PROJECT：30 天
GLOBAL：90 天
USER：365 天
```

检索默认只返回未过期记忆。

## 4. save 写入策略

当前接口：

```java
void save(List<Message> messages, String runId)
```

流程：

```text
1. 接收最近一组 Spring AI Message
2. 使用 PromptConfig.MEMORY_PROMPT + memoryAgent 做结构化记忆抽取
3. 输入给抽取 Agent 的内容：
   - runId
   - 近期 messages：type + text
   - existingMemories：从 ES 中召回的少量相关旧记忆
4. 抽取结果 RawMemories.memories 中每条包含：
   - action: ADD / UPDATE / DELETE / NONE
   - type: SESSION / PROJECT / GLOBAL / USER
   - content: 原子化记忆正文
   - scope: session_only / project / global / cross_session
   - confidence / importance / ttlSeconds / tags / dedupeKey / existingMemoryId
5. 根据 action 处理：
   - NONE：跳过
   - DELETE：按 existingMemoryId 删除 ES 文档
   - UPDATE：按 existingMemoryId 覆盖旧记忆内容，并保留/累加部分强化状态
   - ADD：转换为 MemoryRecord 后进入 saveInternal
6. ADD 保存时：
   - 先 tryHit
   - 命中：strengthenMemory
   - 未命中：indexMemory 新建记忆
```

注意：写入失败会记录 `AUDIT_MEMORY_SAVE_FAILED` 日志，但不向外抛出，避免长期记忆能力影响主 Agent 会话稳定性。

## 5. 抽取 Agent 提示词

长期记忆抽取 prompt 位于：

```text
src/main/java/com/agentcode/config/PromptConfig.java
```

其中：

```text
MEMORY_PROMPT：定义输出 JSON、分类规则、保存边界、敏感信息过滤
MEMORY_USER：传入 runId、messages、existingMemories
```

抽取 Agent 的主要分类原则：

```text
USER：明确表达长期个人偏好、习惯、技能、跨会话约束
PROJECT：项目/仓库约定、技术栈、项目踩坑经验
GLOBAL：跨项目通用经验，但不一定是用户偏好
SESSION：只在当前会话/任务内有意义的临时信息
```

默认不保存：

```text
1. 单纯提问但没有形成稳定事实
2. 工具调用中间日志
3. 原始错误栈，除非已经总结成可复用结论
4. 密码、token、密钥、个人隐私
5. 临时执行意图，没有长期价值

## 6. tryHit 命中策略

`tryHit` 不做分层升级，只做“同一条记忆”的判断。

流程：

```text
1. 对候选记忆做混排召回：
   - BM25 match content
   - kNN content_vector
   - rank.rrf 融合
2. 对同一 query vector 做纯 kNN 检索：
   - 得到 memoryId -> cosineScore
3. 遍历混排候选：
   - cosineScore >= 0.85
   - 且候选 type 与当前记忆 type 相同
   - 判定为命中
4. 命中后调用 strengthenMemory
5. 否则返回 false，由调用方新建记忆
```

关键阈值：

```text
MATCH_THRESHOLD = 0.85
```

## 7. strengthenMemory 强化策略

命中旧记忆后：

```text
hitCount + 1
confidence 提升
updateAt 刷新为当前时间
ttl 按当前 type 重置
meta.hitRunIds 记录不同 runId
meta.distinctHitCount 更新为不同 runId 数量
```

置信度提升：

```text
increment = max(0.02, 0.08 × rawMemory.confidence)
confidence = min(1.0, confidence + increment)
```

## 8. 记忆升级策略

当前升级依据 `meta.distinctHitCount`，避免同一 run 内重复刷屏导致误升级。

规则：

```text
SESSION -> PROJECT：distinctHitCount >= 3
PROJECT -> GLOBAL：distinctHitCount >= 5
GLOBAL -> USER：distinctHitCount >= 8
```

后续可以进一步改成：

```text
SESSION -> PROJECT：同一 projectId 下出现 >= N 个不同 runId
PROJECT -> GLOBAL：出现 >= N 个不同 projectId
GLOBAL -> USER：用户显式确认或多次跨项目稳定命中
```

## 9. search 分层召回策略

当前接口：

```java
List<MemoryRecord> search(String content)
```

默认总召回量：

```text
DEFAULT_SEARCH_TOP_K = 20
```

策略：

```text
1. 对 content 生成 embedding
2. 先查 SESSION 层：
   - SESSION 最多占 8 条
3. 用纯 kNN 计算候选 cosine：
   - 如果 SESSION 最高 cosine < 0.72，则丢弃全部 SESSION 结果
4. 剩余配额分给 PROJECT / GLOBAL / USER
5. 合并结果
6. 按 memoryId 去重
7. 过滤过期记忆
8. 业务重排
```

关键阈值：

```text
SESSION_QUALITY_THRESHOLD = 0.72
```

## 10. 业务重排公式

混排 score 只作为基础相关度，最终还会叠加记忆业务因子。

```text
businessScore =
    (rrfScore + 0.05 × cosineScore)
  × recencyBoost
  × confidenceBoost
  × typeBoost
  × hitCountBoost
```

各因子：

```text
recencyBoost = 1 + 0.5 × exp(-ageHours / 24)
confidenceBoost = 0.8 + 0.4 × confidence
hitCountBoost = 1 + 0.1 × log2(1 + hitCount)
```

typeBoost：

```text
USER = 1.2
GLOBAL = 1.1
PROJECT = 1.0
SESSION = 0.9
```

## 11. Elasticsearch 索引

索引名：

```text
agent_memory
```

mapping：

```text
memoryId：keyword
content：text
content_vector：dense_vector, cosine
type：keyword
confidence：float
updateAt：date
ttl：integer
hitCount：integer
meta：object
```

创建索引时机：

```text
第一次 save / search 时 lazy ensureIndex
```

## 12. 混合检索方式

当前使用 ES 原生混合检索：

```text
query.match(content)
+
knn(content_vector)
+
rank.rrf
```

其中：

```text
query 阶段负责 BM25 倒排召回
knn 阶段负责 dense_vector 语义召回
rank.rrf 负责融合排序
```

`tryHit` 中会额外调用一次纯 kNN，用来拿真实 cosineScore。

## 13. 配置项

### Elasticsearch

`application.yml` 当前支持：

```yaml
spring:
  elasticsearch:
    uris: ${ES_URIS:http://localhost:9200}
    connection-timeout: ${ES_CONNECTION_TIMEOUT:5s}
    socket-timeout: ${ES_SOCKET_TIMEOUT:30s}
```

### 向量模型

`HybridMemoryStore` 需要 Spring AI 的 `EmbeddingModel`。如果项目同时引入 OpenAI 和 DashScope starter，需要显式选择 embedding provider。

OpenAI 向量模型：

```yaml
spring:
  ai:
    model:
      embedding: openai
    openai:
      api-key: ${OPENAI_API_KEY}
      embedding:
        options:
          model: text-embedding-3-small
          dimensions: 1536
```

DashScope 向量模型：

```yaml
spring:
  ai:
    model:
      embedding: dashscope
    dashscope:
      api-key: ${DASHSCOPE_API_KEY}
      embedding:
        options:
          model: text-embedding-v3
          dimensions: 1024
```

对应环境变量：

```text
AI_EMBEDDING_MODEL_PROVIDER=openai 或 dashscope
OPENAI_API_KEY=...
OPENAI_EMBEDDING_MODEL=text-embedding-3-small
OPENAI_EMBEDDING_DIMENSIONS=1536
DASHSCOPE_API_KEY=...
DASHSCOPE_EMBEDDING_MODEL=text-embedding-v3
DASHSCOPE_EMBEDDING_DIMENSIONS=1024
```

`ensureIndex()` 创建 ES 索引时会调用 `embeddingModel.dimensions()`。如果模型维度探测失败，代码默认回退到 `1536`。因此建议显式配置和实际 embedding 模型一致的维度。

### 排除 Spring AI VectorStore 自动装配

当前暂时排除 Spring AI Elasticsearch VectorStore 自动装配：

```yaml
spring:
  autoconfigure:
    exclude:
      - org.springframework.ai.vectorstore.elasticsearch.autoconfigure.ElasticsearchVectorStoreAutoConfiguration
```

原因：当前 `HybridMemoryStore` 自己管理 `agent_memory` 索引，避免和 Spring AI VectorStore 的默认索引冲突。

## 14. 已知不足

当前实现仍有以下不足：

```text
1. projectId / sessionId / workspace 尚未完整建模
2. memory_search 工具尚未注册给主 Agent
3. save 尚未由会话生命周期 Hook 自动触发
4. existingMemories 目前只召回少量相关旧记忆，还没有按 scope 精细裁剪
5. 记忆去重主要依赖 cosine + type，dedupeKey 还只存在 meta，未成为 ES 一等索引字段
6. 升级阈值使用 distinctHitCount，还不够业务化
7. 尚未实现并发多路记忆搜索与统一超时控制
```

## 15. 下一步建议

短期：

```text
1. 给 Agent 注册 memory_search 工具
2. 在会话结束后调用 MemoryStore.save(messages, runId)
3. 在 meta 中补充 workspace / projectId / sessionId
4. 将 dedupeKey 提升为 MemoryRecord / ES 顶层字段
```

中期：

```text
1. 引入 distinctProjectCount
2. 引入 project -> global -> user 的显式确认机制
3. 增加 memory_search 并发召回 USER / PROJECT / GLOBAL / SESSION
4. 增加过期记忆 fallback：高 confidence 过期记忆可低权重召回
```

长期：

```text
1. 考虑情景记忆时间线
2. 考虑实体关系图 / knowledge graph
3. 考虑记忆冲突解决和用户修正入口
```