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

### type 过滤必须挂两处（已修复的坑）

外层 `query.bool.filter` 里的 `type` 过滤 **不会作用于 `knn` 子句**，
`knn` 有自己独立的 `filter`。只写外层时，向量那一腿会跨类型召回，
"分层召回 SESSION / PROJECT / GLOBAL / USER" 名存实亡。

实测（同一条 USER 记忆与一条语义相近的 PROJECT 记忆，外层 `filter(type=USER)`）：

```text
knn 带 filter    -> hits = [p-user-1]                 正确
knn 不带 filter  -> hits = [p-user-1, p-proj-1]       跨类型泄漏
```

因此 `hybridSearchByType()` 在 `query` 与 `knn` 上各挂一份 type 过滤
（`type == null` 时两边都不加，供 `collectExistingMemories()` 跨类型取上下文）。
`scripts/memory-smoke.py` 的 [5] 与 Java 探针都把这条保留了回归断言。

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

本地容器由 `scripts/es-up.sh` 负责（无账号密码认证）：

```bash
scripts/es-up.sh                       # 默认容器名 es01 / 端口 9200 / 镜像 8.15.3
ES_CONTAINER=es-test ES_PORT=9201 scripts/es-up.sh
```

脚本做四件事，都是实测出来的必要项：

1. 单节点 + `xpack.security.enabled=false`，不启用账号密码
2. `--restart unless-stopped`：否则宿主机重启后容器变成 `Exited(255)`，
   表面症状是"记忆模块连不上 localhost:9200"，很容易误判成代码问题
3. **激活 30 天 trial license**：`rank.rrf`（混排）和近似 `knn`（dense_vector）
   在 ES 里属于企业级特性，默认 `basic` license 下会直接返回
   `403 security_exception: current license is non-compliant for [Reciprocal Rank Fusion (RRF)]`，
   即 `hybridSearchByType()` 与 `vectorSearch()` 两条腿全挂。trial 每个集群只能激活一次，
   重复调用返回 403 属正常
4. 建 `agent_memory*` 索引模板设 `number_of_replicas=0`：单节点默认 1 副本永远分配不出来，
   索引会卡在 yellow。（`_cluster/settings` 里写 `index.number_of_replicas` /
   `archival.index.number_of_replicas` 在 8.15 都被拒绝，只能走索引模板）

### 向量模型

`HybridMemoryStore` 需要 Spring AI 的 `EmbeddingModel`。如果项目同时引入 OpenAI 和 DashScope starter，需要显式选择 embedding provider。

**base-url 陷阱（必须注意）**：`DashScopeApi` 用 `baseUrl + embeddingsPath` 拼请求地址，
而 `embeddingsPath` 常量本身已经带 `/api/v1`：

```text
/api/v1/services/embeddings/text-embedding/text-embedding
```

所以 `spring.ai.dashscope.base-url` **只能填主机，不能带 `/api/v1`**。
带上了会拼成 `/api/v1/api/v1/services/...`，实测报
`NonTransientAiException: 404`（公共端点和专属 MaaS 实例都一样）。

```yaml
spring:
  ai:
    model:
      embedding: dashscope
    dashscope:
      api-key: ${DASHSCOPE_API_KEY}
      base-url: https://ws-xxxx.cn-beijing.maas.aliyuncs.com   # 不带 /api/v1
      embedding:
        options:
          model: qwen3.7-text-embedding
          dimensions: 1024
```

OpenAI 向量模型的等价配置：

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

对应环境变量（推荐写进 `.env`，用 `scripts/with-env.sh` 注入，Spring Boot 不会自己读 `.env`）：

```text
AI_EMBEDDING_MODEL_PROVIDER=openai 或 dashscope
OPENAI_API_KEY=...
OPENAI_EMBEDDING_MODEL=text-embedding-3-small
OPENAI_EMBEDDING_DIMENSIONS=1536
DASHSCOPE_API_KEY=...
DASHSCOPE_BASE_URL=...            # 不含 /api/v1
DASHSCOPE_EMBEDDING_MODEL=qwen3.7-text-embedding
DASHSCOPE_EMBEDDING_DIMENSIONS=1024
```

`ensureIndex()` 创建 ES 索引时会调用 `embeddingModel.dimensions()`。
注意 `DashScopeEmbeddingModel` 没有覆写 `dimensions()`，走的是
`AbstractEmbeddingModel` 的实现：**真实发一次 embedding 请求来探测维度**，
所以它会吃 API Key / base-url 配置的影响。探测失败时代码回退 `1536`，
与 1024 维模型不匹配会让 kNN 直接报错，因此必须显式配置一致的维度。
现在回退时会打 `AUDIT_MEMORY_EMBEDDING_DIMS_PROBE_FAILED` /
`AUDIT_MEMORY_EMBEDDING_DIMS_UNKNOWN` 两行告警，不再静默。

实测数据（`qwen3.7-text-embedding`，2026-09-01）：

```text
embed() 输出维度        = 1024
dimensions() 探测结果   = 1024（与 embed() 一致）
```

### 关闭未使用的 OpenAI 子模型

`spring.ai.model.chat` / `embedding` 只控制这两类。
`spring-ai-starter-model-openai` 里 audio / image / moderation 的自动装配各自独立生效，
未显式关闭时即使全部走 dashscope，也会因为 `spring.ai.openai.api-key` 为空而启动失败：

```text
OpenAI API key must be set. Use the connection property: spring.ai.openai.api-key
  or spring.ai.openai.speech.api-key property.
（bean: openAiAudioSpeechModel）
```

`application.yml` 已固定为：

```yaml
spring:
  ai:
    model:
      image: ${AI_IMAGE_MODEL:none}
      moderation: ${AI_MODERATION_MODEL:none}
      audio:
        speech: ${AI_AUDIO_SPEECH_MODEL:none}
        transcription: ${AI_AUDIO_TRANSCRIPTION_MODEL:none}
```

### 排除 Spring AI VectorStore 自动装配

当前暂时排除 Spring AI Elasticsearch VectorStore 自动装配：

```yaml
spring:
  autoconfigure:
    exclude:
      - org.springframework.ai.vectorstore.elasticsearch.autoconfigure.ElasticsearchVectorStoreAutoConfiguration
```

原因：当前 `HybridMemoryStore` 自己管理 `agent_memory` 索引，避免和 Spring AI VectorStore 的默认索引冲突。

## 14. 本地环境与冒烟验证

一键跑通顺序：

```bash
# 1. ES（无认证 + trial license + RRF 能力探测）
scripts/es-up.sh

# 2. 记忆链路冒烟（不依赖 Spring 启动，直连真模型 + 真 ES）
scripts/with-env.sh python3 scripts/memory-smoke.py

# 3. 启动应用（.env 通过 with-env.sh 注入进程环境）
scripts/with-env.sh mvn -o -Dmaven.repo.local=../.m2repo spring-boot:run

# 4. 验证 ES 客户端真的连通
curl -s localhost:8080/actuator/health | jq .components.elasticsearch
```

`scripts/memory-smoke.py` 校验的是 `HybridMemoryStore` 依赖的外部事实，7 组断言：

```text
[1] ES 可达 + 版本支持 rank.rrf（>= 8.11）
[2] 向量模型可调用、维度与配置一致
[3] 用 ensureIndex() 同样的 mapping 建索引，确认 dense_vector/cosine/dims
[4] BM25(query.bool) + kNN + rank.rrf 混排能命中
[5] type 隔离回归：缺 knn.filter 会被向量腿跨类型打穿（脚本内含反证步骤）
[6] 纯 kNN 返回 cosine 分数，且落在 [0,1]、语义更近者分数更高
[7] partial update 不丢向量与正文（对应 strengthenMemory -> updateMemory）
```

写脚本时踩到两个坑，值得记住：

1. ES 8.x 拒绝缺少/错误的 `Content-Type`，会返回
   `406 Content-Type header [application/x-www-form-urlencoded] is not supported`
2. `POST /_refresh` **不接受 request body**，带 `-d '{}'` 会 400 且容易被忽略；
   结果就是写完立刻查会拿到 0 命中，看起来像"检索逻辑坏了"

`tryHit` 阈值校准参考（同一模型、真实 cosine）：

```text
"包管理器用什么" vs "用户偏好使用 pnpm 而不是 npm 管理依赖"  cosine = 0.8207
"包管理器用什么" vs "这个项目使用 Java 17 和 Spring Boot 3.5"  cosine = 0.7050
```

即语义相关但表述不同的两条记忆，cosine 会落在 `MATCH_THRESHOLD = 0.85` 之下。
这意味着 `tryHit` 可能偏严（该合并的没合并，导致重复记忆堆积），
后续做记忆测试时如果发现重复条目，先怀疑这个阈值而不是抽取 prompt。

## 15. 审计日志（AUDIT_MEMORY_*）

沿用项目既有的 `AUDIT_<事件> k=v k=v` 约定（与 `AuditedChatModel`、
`ToolMetricsInterceptor` 一致），`HybridMemoryStore` 用 `@Slf4j` 的 `log.info`
记录关键记忆动作，正文一律经 `brief()` 压成单行并截断（默认 120 字符），
保证可 grep、可回放。

写入链路（`save`）：

| 事件 | 级别 | 含义 |
| --- | --- | --- |
| `AUDIT_MEMORY_INDEX_CREATED` | info | 首次建 `agent_memory` 索引，含 dims/similarity |
| `AUDIT_MEMORY_INDEX_READY` | info | 索引已存在（不重复探测维度，避免多余 API 调用） |
| `AUDIT_MEMORY_EMBEDDING_READY` | info | 探测到的 embedding 维度 |
| `AUDIT_MEMORY_EMBEDDING_DIMS_UNKNOWN` / `_PROBE_FAILED` | warn | 维度探测失败并回退 1536 |
| `AUDIT_MEMORY_EXTRACT` | info | 抽取结果条数（`extracted=0` 表示本轮没记住任何东西） |
| `AUDIT_MEMORY_EXTRACT_AGENT` | info | 抽取 Agent 调用耗时与条数 |
| `AUDIT_MEMORY_EXTRACT_RAW` | debug | 抽取 Agent 原始输出，定位"为什么没记住" |
| `AUDIT_MEMORY_EXTRACT_EMPTY` / `_PARSE_FAILED` / `_FAILED` | info/warn | 空输出 / JSON 解析失败 / 各阶段异常（stage 区分 agent、prompt、existingMemories） |
| `AUDIT_MEMORY_EXTRACT_CONTEXT` | debug | 喂给抽取 Agent 的旧记忆条数，决定 ADD 还是 UPDATE |
| `AUDIT_MEMORY_APPLY` | info | 每条抽取结果的决策落库前记录：action/type/memoryId/confidence/ttl/dedupeKey/content |
| `AUDIT_MEMORY_TRY_HIT` | info/debug | 命中记 info（含 cosine、threshold、rrfScore），未命中候选记 debug |
| `AUDIT_MEMORY_ADD` | info | 新建记忆（含 ES 返回的 esResult） |
| `AUDIT_MEMORY_UPDATE` | info | 覆盖更新，同时记录 oldContent 与 newContent |
| `AUDIT_MEMORY_UPDATE_FALLBACK_ADD` | info | 抽取给了 existingMemoryId 但库里查不到，退化为按该 id 新建 |
| `AUDIT_MEMORY_STRENGTHEN` | info | 命中强化：hitCount / distinctHits / confidence 前后值 / 是否升级 |
| `AUDIT_MEMORY_PROMOTE` | info | 类型升级 SESSION->PROJECT->GLOBAL->USER |
| `AUDIT_MEMORY_DELETE` | info | 删除记忆 |
| `AUDIT_MEMORY_SKIP` | info/warn | 因 action=DELETE 缺 id、正文为空、向量为空而跳过 |
| `AUDIT_MEMORY_SAVE_DONE` | info | 本轮汇总：extracted / applied / durationMs |
| `AUDIT_MEMORY_SAVE_FAILED` | warn | 整体失败（不外抛，避免拖垮主会话） |

召回链路（`search`）：

| 事件 | 级别 | 含义 |
| --- | --- | --- |
| `AUDIT_MEMORY_SEARCH` | info | query、candidates、returned、topHits 采样、byType 统计、耗时 |
| `AUDIT_MEMORY_SEARCH_FAILED` / `_SKIPPED` | warn | ES 异常 / 向量为空 |
| `AUDIT_MEMORY_LAYER_SESSION_DROPPED` | debug | SESSION 最高 cosine 低于 0.72 被整体丢弃 |
| `AUDIT_MEMORY_LAYER_QUOTA` | debug | 分层配额分配结果 |
| `AUDIT_MEMORY_EMBED` / `_EMPTY` / `_FAILED` | debug/warn | 单次 embedding 调用的维度与耗时 |

常用排查命令：

```bash
# 一次会话里记忆相关的完整时间线
grep -aoE 'AUDIT_MEMORY_[A-Z_]+' logs/agentcode.log | sort | uniq -c | sort -rn

# 只看真正落库的记忆动作
grep -a 'AUDIT_MEMORY_\(ADD\|UPDATE\|STRENGTHEN\|PROMOTE\|DELETE\)' logs/agentcode.log

# 追某一条记忆的终身轨迹
grep -a 'memoryId=<id 前 8 位>' logs/agentcode.log

# 按 runId 串起一次会话
grep -a "AUDIT_MEMORY_.*runId=<runId>" logs/agentcode.log
```

`com.agentcode` 默认 `DEBUG`（`AGENT_LOG_LEVEL`），因此 `tryHit` 的逐候选打分、
embedding 耗时等细节日志本地默认可见；生产上如嫌吵可只调
`logging.level.com.agentcode.memory=INFO`，`log.isDebugEnabled()` 已做保护。

## 16. 已知不足

当前实现仍有以下不足：

```text
1. projectId / sessionId / workspace 尚未完整建模
2. memory_search 工具尚未注册给主 Agent
3. save 尚未由会话生命周期 Hook 自动触发
4. existingMemories 目前只召回少量相关旧记忆，还没有按 scope 精细裁剪
5. 记忆去重主要依赖 cosine + type，dedupeKey 还只存在 meta，未成为 ES 一等索引字段
6. 升级阈值使用 distinctHitCount，还不够业务化
7. 尚未实现并发多路记忆搜索与统一超时控制
8. MATCH_THRESHOLD = 0.85 偏严：实测语义相关但表述不同的两条记忆 cosine 只有 0.82，
   会导致该强化的没强化、重复记忆堆积（见 14 节实测数据）
9. 依赖 ES trial/企业 license：basic license 下 RRF 与近似 kNN 直接 403，
   部署环境若为 basic 需要改成 script_score 精确向量检索 + 客户端 RRF
```

## 17. 下一步建议

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