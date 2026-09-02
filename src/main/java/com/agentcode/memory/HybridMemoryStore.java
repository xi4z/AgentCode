package com.agentcode.memory;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.mapping.DenseVectorSimilarity;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.agentcode.config.PromptConfig;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 基于 Elasticsearch 的长期记忆实现。
 *
 * <p>当前策略：
 * <ul>
 *   <li>save：ReActAgent 抽取 RawMemories，再转换 MemoryRecord；命中强化，未命中保存</li>
 *   <li>tryHit：只尝试命中同类型记忆，并用纯向量 cosine 相似度判断是否同一条</li>
 *   <li>search：分层召回 SESSION / PROJECT / GLOBAL / USER，再按业务分数重排</li>
 *   <li>strengthen：提升 hitCount / confidence / TTL，并满足阈值后逐步升级记忆类型</li>
 * </ul>
 */
@Slf4j
@Component
public class HybridMemoryStore implements MemoryStore {

    private static final String INDEX_NAME = "agent_memory";
    private static final int DEFAULT_EMBEDDING_DIMENSIONS = 1536;
    private static final int DEFAULT_SEARCH_TOP_K = 20;
    private static final int SESSION_MAX_RESULTS = 8;
    private static final int HIT_TOP_K = 10;
    private static final int COSINE_TOP_K = 20;

    /**
     * tryHit 向量命中阈值，<b>语义为真实余弦</b>（见 {@link #toCosine(Double)}：
     * ES kNN 的 _score 是 (1+cosine)/2，历史上被直接当余弦用，导致阈值语义整体偏移）。
     * <p>
     * 真实余弦标定（qwen3.7-text-embedding-flash，1024 维）：
     * <pre>
     * 同义改写（应判同一条）        0.6952 / 0.7639 / 0.7860 / 0.8467 / 0.8666
     * 同主题但事实不同（应各自独立）  0.5548 ~ 0.7115
     * </pre>
     * 0.80 落在这两组之间：高于"不同事实"的上界 0.7115，能抓回真正的同义改写。
     * 旧值 0.85 在 _score 语义下等价真实余弦 0.70，会把"同主题不同事实"误判为同一条
     * （实测把"写前端习惯用 React"并进"conventional commits 规范"），调数字前必须先确认单位。
     * <p>
     * 该阈值与具体 embedding 模型绑定，换模型必须重新标定；
     * 回归用例见 scripts/memory-suite-large.mjs 的 G3（去重）与 G4（误合并）两组。
     */
    private static final double MATCH_THRESHOLD = 0.80;
    private static final double SESSION_QUALITY_THRESHOLD = 0.72;

    /**
     * 抽取 Agent 的最大尝试次数（含首次）。真实 chat 模型偶发超时后，图节点会把异常文本
     * 当成模型输出返回；原实现一次失败就丢掉整轮记忆（大测试集实测 3/8 个批次静默全灭）。
     */
    private static final int EXTRACT_MAX_ATTEMPTS = 3;

    /** 第 n 次重试前的退避毫秒（越界取末位）。抽取在记忆线程池异步执行，退避不阻塞主会话。 */
    private static final long[] EXTRACT_BACKOFF_MS = {500L, 1500L};

    /**
     * 审计日志约定：事件名统一用 AUDIT_MEMORY_* 前缀，字段用 k=v，正文用 content="..." 包住。
     * 记忆正文可能含换行与超长文本，一律经 brief() 压成单行并截断，保证日志可 grep、可回放。
     */
    private static final int AUDIT_CONTENT_MAX_LENGTH = 120;
    private static final int AUDIT_HIT_CONTENT_MAX_LENGTH = 40;
    private static final int AUDIT_HIT_SAMPLE = 5;

    private static final String ACTION_ADD = "ADD";
    private static final String ACTION_UPDATE = "UPDATE";
    private static final String ACTION_DELETE = "DELETE";
    private static final String ACTION_NONE = "NONE";

    private final ChatModel chatModel;
    private final EmbeddingModel embeddingModel;
    private final ElasticsearchClient esClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * memory_agent 懒加载：不再构造函数里无条件 new（启动即建会拉起 ChatModel 相关初始化），
     * 首次记忆抽取时才创建，见 {@link #lazyMemoryAgent()}。
     */
    private volatile ReactAgent memoryAgent;

    private volatile boolean indexInitialized = false;

    public HybridMemoryStore(ChatModel chatModel,
                             EmbeddingModel embeddingModel,
                             ElasticsearchClient esClient) {
        this.chatModel = chatModel;
        this.embeddingModel = embeddingModel;
        this.esClient = esClient;
        // memory_agent 改为懒加载，构造阶段只保存依赖，不创建 Agent。
    }

    /**
     * memory_agent 懒加载（volatile 双检锁）：首次抽取记忆时才创建，避免启动即初始化模型链路。
     */
    private ReactAgent lazyMemoryAgent() {
        ReactAgent agent = memoryAgent;
        if (agent == null) {
            synchronized (this) {
                agent = memoryAgent;
                if (agent == null) {
                    agent = ReactAgent.builder()
                            .name("memory_agent")
                            .description("一个用于分析输入对话中有什么值得记入长期记忆中的Agent")
                            .model(chatModel)
                            .systemPrompt(PromptConfig.MEMORY_PROMPT)
                            .outputType(RawMemories.class)
                            .build();
                    memoryAgent = agent;
                }
            }
        }
        return agent;
    }

    @Override
    public void save(List<Message> messages, String runId) {
        if (messages == null || messages.isEmpty()) {
            return;
        }

        long start = System.nanoTime();
        try {
            // ensureIndex 放在 try 内：索引初始化失败也不能把异常漏给调用方，保持 save 整体吞异常语义。
            ensureIndex();

            RawMemories memories = extractMemory(messages, runId);
            List<RawMemories.RawMemory> extracted =
                    (memories == null || memories.getMemories() == null) ? List.of() : memories.getMemories();

            log.info("AUDIT_MEMORY_EXTRACT runId={} messages={} extracted={}",
                    runId, messages.size(), extracted.size());

            int applied = 0;
            for (RawMemories.RawMemory rawMemory : extracted) {
                if (applyRawMemory(rawMemory, runId)) {
                    applied++;
                }
            }

            log.info("AUDIT_MEMORY_SAVE_DONE runId={} extracted={} applied={} durationMs={}",
                    runId, extracted.size(), applied, (System.nanoTime() - start) / 1_000_000);
        } catch (Exception e) {
            // 记忆写入失败不应该打断主 Agent 会话流程。
            log.warn("AUDIT_MEMORY_SAVE_FAILED runId={} error={}", runId, e.getMessage(), e);
        }
    }

    @Override
    public List<MemoryRecord> search(String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        long start = System.nanoTime();
        try {
            ensureIndex();
            List<Float> vector = embed(content);
            if (vector.isEmpty()) {
                log.warn("AUDIT_MEMORY_SEARCH_SKIPPED reason=emptyEmbedding query=\"{}\"", brief(content));
                return List.of();
            }

            List<ScoredMemory> candidates = layeredSearch(content, vector, DEFAULT_SEARCH_TOP_K);
            List<MemoryRecord> result = reRank(candidates);
            log.info("AUDIT_MEMORY_SEARCH query=\"{}\" dims={} candidates={} returned={} topHits={} byType={} durationMs={}",
                    brief(content), vector.size(), candidates.size(), result.size(),
                    summarize(result), countByType(result),
                    (System.nanoTime() - start) / 1_000_000);
            return result;
        } catch (Exception e) {
            // 搜索失败降级为空列表，绝不把异常抛给主链路（ES 故障不应杀死整轮 run），
            // 与 save() 的吞异常语义保持一致。
            log.warn("AUDIT_MEMORY_SEARCH_FAILED query=\"{}\" error={}", brief(content), e.getMessage(), e);
            return List.of();
        }
    }

    private boolean applyRawMemory(RawMemories.RawMemory rawMemory, String runId) {
        if (rawMemory == null) {
            return false;
        }

        String action = normalizeAction(rawMemory.getAction());

        if (ACTION_NONE.equals(action)) {
            return false;
        }

        if (ACTION_DELETE.equals(action)) {
            if (notBlank(rawMemory.getExistingMemoryId())) {
                deleteMemory(rawMemory.getExistingMemoryId());
                return true;
            }
            log.info("AUDIT_MEMORY_SKIP runId={} action={} reason=missingExistingMemoryId", runId, action);
            return false;
        }

        if (!notBlank(rawMemory.getContent())) {
            log.info("AUDIT_MEMORY_SKIP runId={} action={} reason=emptyContent", runId, action);
            return false;
        }

        MemoryRecord candidate = toMemoryRecord(rawMemory, runId, action);
        List<Float> vector = embed(candidate.getContent());
        if (vector.isEmpty()) {
            log.warn("AUDIT_MEMORY_SKIP runId={} action={} reason=emptyEmbedding memoryId={}",
                    runId, action, candidate.getMemoryId());
            return false;
        }

        // 抽取 Agent 的决策是本模块最关键的审计点：一条记忆写库前先落一行决策记录。
        log.info("AUDIT_MEMORY_APPLY runId={} action={} type={} memoryId={} confidence={} ttl={} dedupeKey={} existingMemoryId={} content=\"{}\"",
                runId, action, candidate.getType(), candidate.getMemoryId(),
                num(candidate.getConfidence()), candidate.getTtl(),
                candidate.getMeta().get("dedupeKey"), rawMemory.getExistingMemoryId(),
                brief(candidate.getContent()));

        // 向量腿先裁决"是不是同一条记忆"，LLM 给的 existingMemoryId 只作兜底。
        // 必须放在 UPDATE 分支之前：否则近似重复会被 LLM 的 UPDATE 提前吸收，tryHit 只有在
        // 真正新增时才有机会跑（实测一轮 0/23 命中，向量去重腿形同虚设）。
        MemoryRecord matched = null;
        try {
            matched = findVectorMatch(candidate, vector);
        } catch (IOException e) {
            log.warn("AUDIT_MEMORY_DEDUPE_FAILED runId={} memoryId={} error={}",
                    runId, candidate.getMemoryId(), e.getMessage(), e);
        }

        if (matched != null) {
            String llmTarget = rawMemory.getExistingMemoryId();
            if (ACTION_UPDATE.equals(action) && notBlank(llmTarget) && !llmTarget.equals(matched.getMemoryId())) {
                // LLM 指错了合并目标：以向量结果为准，并留一行冲突痕迹便于回查抽取质量
                log.info("AUDIT_MEMORY_DEDUPE_CONFLICT runId={} llmTarget={} vectorMatched={} content=\"{}\"",
                        runId, llmTarget, matched.getMemoryId(), brief(candidate.getContent()));
            }
            // 命中同一条：必须走覆盖语义，保留抽取 Agent 归一化后的新表述与对应的新向量。
            // 不能只调 strengthenMemory —— 那样内容不会被更新，UPDATE 意图会被静默降级成纯计数；
            // 且 action=UPDATE 时候选记录的 id 就是 LLM 指定的目标 id，向量腿"命中"的正是它自己。
            log.info("AUDIT_MEMORY_DEDUPE_VECTOR runId={} incomingId={} matchedId={} llmAction={} llmTarget={}",
                    runId, candidate.getMemoryId(), matched.getMemoryId(), action,
                    rawMemory.getExistingMemoryId());
            updateExistingMemory(candidate, matched.getMemoryId(), vector);
            return true;
        }

        if (ACTION_UPDATE.equals(action) && notBlank(rawMemory.getExistingMemoryId())) {
            // 向量未命中但 LLM 指名了 id：按 id 覆盖（id 查不到时内部会兜底新建）
            updateExistingMemory(candidate, rawMemory.getExistingMemoryId(), vector);
            return true;
        }

        indexNewMemory(candidate, vector);
        return true;
    }

    /**
     * 真正的存记忆方法：先尝试命中旧记忆，命中则强化，否则新建。
     */
    private void save(String memory, String runId, MemoryRecord.MemoryType memoryType) {
        if (memory == null || memory.isBlank()) {
            return;
        }
        ensureIndex();

        MemoryRecord candidate = buildRecord(memory, runId, memoryType);
        List<Float> vector = embed(candidate.getContent());
        if (vector.isEmpty()) {
            return;
        }
        saveInternal(candidate, vector);
    }

    private void saveInternal(MemoryRecord candidate, List<Float> vector) {
        try {
            if (tryHit(candidate, vector)) {
                return;
            }
        } catch (IOException e) {
            log.warn("AUDIT_MEMORY_ADD_FAILED memoryId={} runId={} error={}",
                    candidate.getMemoryId(), metaValue(candidate, "runId"), e.getMessage(), e);
            throw new RuntimeException("Save agent memory failed", e);
        }
        indexNewMemory(candidate, vector);
    }

    /**
     * 直接落一条新记忆。向量去重已由调用方裁决完毕（applyRawMemory 的 findVectorMatch），
     * 这里不再重复跑 tryHit，免得一次写入打两趟 ES。
     */
    private void indexNewMemory(MemoryRecord candidate, List<Float> vector) {
        try {
            String esResult = indexMemory(candidate, vector);
            log.info("AUDIT_MEMORY_ADD memoryId={} runId={} type={} confidence={} ttl={} dims={} esResult={} content=\"{}\"",
                    candidate.getMemoryId(), metaValue(candidate, "runId"), candidate.getType(),
                    num(candidate.getConfidence()), candidate.getTtl(), vector.size(), esResult,
                    brief(candidate.getContent()));
        } catch (IOException e) {
            log.warn("AUDIT_MEMORY_ADD_FAILED memoryId={} runId={} error={}",
                    candidate.getMemoryId(), metaValue(candidate, "runId"), e.getMessage(), e);
            throw new RuntimeException("Save agent memory failed", e);
        }
    }

    /**
     * 更新已有记忆。这里使用 partial update，避免把大向量带回 Java 再写回。
     */
    private void updateMemory(MemoryRecord memory) {
        if (memory == null || memory.getMemoryId() == null) {
            return;
        }
        Map<String, Object> partialDoc = new HashMap<>();
        partialDoc.put("type", memory.getType().name());
        partialDoc.put("confidence", memory.getConfidence());
        partialDoc.put("updateAt", memory.getUpdateAt() == null ? LocalDateTime.now().toString() : memory.getUpdateAt().toString());
        partialDoc.put("ttl", memory.getTtl());
        partialDoc.put("hitCount", memory.getHitCount());
        partialDoc.put("meta", memory.getMeta() == null ? Map.of() : memory.getMeta());

        try {
            esClient.update(u -> u
                            .index(INDEX_NAME)
                            .id(memory.getMemoryId())
                            .doc(partialDoc),
                    MemoryRecord.class
            );
        } catch (IOException e) {
            log.warn("AUDIT_MEMORY_UPDATE_FAILED memoryId={} error={}",
                    memory.getMemoryId(), e.getMessage(), e);
            throw new RuntimeException("Update agent memory failed", e);
        }
    }

    private void updateExistingMemory(MemoryRecord candidate, String existingMemoryId, List<Float> vector) {
        MemoryRecord existing = getMemory(existingMemoryId);
        if (existing == null) {
            // 抽取 Agent 给了 existingMemoryId 但库里查不到：退化成按该 id 新建，保留 id 便于后续对齐。
            candidate.setMemoryId(existingMemoryId);
            try {
                String esResult = indexMemory(candidate, vector);
                log.info("AUDIT_MEMORY_UPDATE_FALLBACK_ADD memoryId={} runId={} type={} esResult={} content=\"{}\"",
                        existingMemoryId, metaValue(candidate, "runId"), candidate.getType(), esResult,
                        brief(candidate.getContent()));
            } catch (IOException e) {
                log.warn("AUDIT_MEMORY_UPDATE_FAILED memoryId={} error={}",
                        existingMemoryId, e.getMessage(), e);
                throw new RuntimeException("Update agent memory failed", e);
            }
            return;
        }

        String oldContent = existing.getContent();
        existing.setMemoryId(existingMemoryId);
        existing.setContent(candidate.getContent());
        existing.setType(candidate.getType());
        existing.setTtl(candidate.getTtl());
        existing.setUpdateAt(LocalDateTime.now());
        existing.setHitCount(existing.getHitCount() + 1);

        double confidence = Math.max(existing.getConfidence(), candidate.getConfidence());
        existing.setConfidence(Math.min(1.0d, confidence + 0.03d));

        Map<String, Object> meta = mergeMeta(existing.getMeta(), candidate.getMeta());
        rememberHit(meta, candidate.getMeta() == null ? null : candidate.getMeta().get("runId"));
        existing.setMeta(meta);

        // 覆盖更新同样是一次"命中强化"，因此也要走升级判定（否则向量命中会丢掉类型升级）
        applyPromotionByHits(existing, meta);

        try {
            // content_vector 已变化，使用全量 index 覆盖，避免旧向量残留。
            String esResult = indexMemory(existing, vector);
            log.info("AUDIT_MEMORY_UPDATE memoryId={} runId={} type={} confidence={} hitCount={} esResult={} oldContent=\"{}\" newContent=\"{}\"",
                    existingMemoryId, metaValue(existing, "runId"), existing.getType(),
                    num(existing.getConfidence()), existing.getHitCount(), esResult,
                    brief(oldContent), brief(existing.getContent()));
        } catch (IOException e) {
            log.warn("AUDIT_MEMORY_UPDATE_FAILED memoryId={} error={}",
                    existingMemoryId, e.getMessage(), e);
            throw new RuntimeException("Update agent memory failed", e);
        }
    }

    private void deleteMemory(String memoryId) {
        try {
            var response = esClient.delete(d -> d.index(INDEX_NAME).id(memoryId));
            log.info("AUDIT_MEMORY_DELETE memoryId={} esResult={}", memoryId, response.result().name());
        } catch (IOException e) {
            log.warn("AUDIT_MEMORY_DELETE_FAILED memoryId={} error={}", memoryId, e.getMessage(), e);
            throw new RuntimeException("Delete agent memory failed", e);
        }
    }

    private MemoryRecord getMemory(String memoryId) {
        try {
            return esClient.get(g -> g.index(INDEX_NAME).id(memoryId), MemoryRecord.class).source();
        } catch (IOException e) {
            log.warn("AUDIT_MEMORY_GET_FAILED memoryId={} error={}", memoryId, e.getMessage(), e);
            throw new RuntimeException("Get agent memory failed", e);
        }
    }

    private void strengthenMemory(MemoryRecord rawMemory, MemoryRecord target) {
        if (target == null) {
            return;
        }

        MemoryRecord.MemoryType typeBefore = target.getType();
        double confidenceBefore = target.getConfidence();

        target.setHitCount(target.getHitCount() + 1);

        double increment = Math.max(0.02d, 0.08d * rawMemory.getConfidence());
        target.setConfidence(Math.min(1.0d, target.getConfidence() + increment));
        target.setUpdateAt(LocalDateTime.now());
        target.setTtl(defaultTtl(target.getType()));

        Map<String, Object> meta = target.getMeta() == null ? new HashMap<>() : new HashMap<>(target.getMeta());
        rememberHit(meta, rawMemory.getMeta() == null ? null : rawMemory.getMeta().get("runId"));
        meta.put("lastMatchContent", rawMemory.getContent());
        target.setMeta(meta);

        // 基础升级策略：优先按“不同 runId 命中数”泛化，避免同一个会话内重复刷屏导致误升级。
        int distinctHits = applyPromotionByHits(target, meta);

        updateMemory(target);

        log.info("AUDIT_MEMORY_STRENGTHEN memoryId={} runId={} hitCount={} distinctHits={} confidence={}->{} type={} promoted={} ttl={} content=\"{}\"",
                target.getMemoryId(), metaValue(rawMemory, "runId"), target.getHitCount(), distinctHits,
                num(confidenceBefore), num(target.getConfidence()), target.getType(),
                typeBefore != target.getType(), target.getTtl(), brief(target.getContent()));
    }

    /**
     * 按"不同 runId 的命中数"做类型升级：SESSION -> PROJECT -> GLOBAL -> USER。
     * <p>
     * 用 distinctHitCount 而不是 hitCount，是为了避免同一会话内重复刷屏把记忆虚高升级。
     * 强化（strengthen）与覆盖更新（update）两条路径共用，保证"被再次命中"这件事
     * 无论走哪条路都能累积升级进度。
     *
     * @return 本次判定使用的 distinctHits，供审计日志输出
     */
    private int applyPromotionByHits(MemoryRecord target, Map<String, Object> meta) {
        int distinctHits = intValue(meta.get("distinctHitCount"), target.getHitCount());
        if (target.getType() == MemoryRecord.MemoryType.SESSION && distinctHits >= 3) {
            promoteType(target, MemoryRecord.MemoryType.PROJECT);
        } else if (target.getType() == MemoryRecord.MemoryType.PROJECT && distinctHits >= 5) {
            promoteType(target, MemoryRecord.MemoryType.GLOBAL);
        } else if (target.getType() == MemoryRecord.MemoryType.GLOBAL && distinctHits >= 8) {
            promoteType(target, MemoryRecord.MemoryType.USER);
        }
        return distinctHits;
    }

    /**
     * 尝试命中一次已有记忆：命中则强化旧记忆并返回 true。
     */
    private boolean tryHit(MemoryRecord memory, List<Float> vector) throws IOException {
        MemoryRecord matched = findVectorMatch(memory, vector);
        if (matched == null) {
            return false;
        }
        strengthenMemory(memory, matched);
        return true;
    }

    /**
     * 向量判定口：找出与 incoming 属于"同一条记忆"的既有记录，找不到返回 null。
     * <p>
     * ADD 与 UPDATE 两条路径共用这一个裁决口 —— 否则抽取 Agent 一句 UPDATE 就绕过了向量去重，
     * tryHit 只能在纯新增时跑到（实测 0/23 命中）。
     * 判定条件 = 混排召回的候选 ∩ 同一 query 向量的真实余弦 >= {@code MATCH_THRESHOLD} ∩ 类型兼容。
     */
    private MemoryRecord findVectorMatch(MemoryRecord incoming, List<Float> vector) throws IOException {
        List<ScoredMemory> candidates = hybridSearchByType(incoming.getContent(), HIT_TOP_K, vector, incoming.getType());
        if (candidates.isEmpty()) {
            log.debug("AUDIT_MEMORY_TRY_HIT memoryId={} candidates=0 result=MISS", incoming.getMemoryId());
            return null;
        }

        Map<String, Double> cosineScores = vectorSearch(vector, COSINE_TOP_K);
        for (ScoredMemory candidate : candidates) {
            MemoryRecord existing = candidate.memory();
            double cosine = cosineScores.getOrDefault(existing.getMemoryId(), 0.0);
            if (cosine >= MATCH_THRESHOLD && compatible(incoming.getType(), existing.getType())) {
                log.info("AUDIT_MEMORY_TRY_HIT incomingId={} matchedId={} runId={} type={} cosine={} threshold={} rrfScore={} result=HIT",
                        incoming.getMemoryId(), existing.getMemoryId(), metaValue(incoming, "runId"),
                        incoming.getType(), num(cosine), MATCH_THRESHOLD, num(candidate.esScore()));
                return existing;
            }
            if (log.isDebugEnabled()) {
                log.debug("AUDIT_MEMORY_TRY_HIT incomingId={} candidateId={} candidateType={} cosine={} threshold={} compatible={} result=SKIP",
                        incoming.getMemoryId(), existing.getMemoryId(), existing.getType(),
                        num(cosine), MATCH_THRESHOLD, compatible(incoming.getType(), existing.getType()));
            }
        }
        log.debug("AUDIT_MEMORY_TRY_HIT incomingId={} candidates={} result=MISS", incoming.getMemoryId(), candidates.size());
        return null;
    }

    /**
     * 对初步召回后的 memories 再按业务字段重新排序。
     */
    private List<MemoryRecord> reRank(List<ScoredMemory> memories) {
        if (memories == null || memories.isEmpty()) {
            return List.of();
        }

        Map<String, ScoredMemory> unique = new LinkedHashMap<>();
        for (ScoredMemory sm : memories) {
            if (sm == null || sm.memory() == null || sm.memory().getMemoryId() == null) {
                continue;
            }
            if (isExpired(sm.memory())) {
                continue;
            }
            unique.merge(sm.memory().getMemoryId(), sm, (a, b) -> businessScore(a) >= businessScore(b) ? a : b);
        }

        return unique.values().stream()
                .sorted(Comparator.comparingDouble(this::businessScore).reversed())
                .map(ScoredMemory::memory)
                .toList();
    }

    private double businessScore(ScoredMemory sm) {
        MemoryRecord memory = sm.memory();
        if (memory == null) {
            return 0.0;
        }

        LocalDateTime updateAt = memory.getUpdateAt() == null ? LocalDateTime.now() : memory.getUpdateAt();
        double ageHours = Math.max(0, Duration.between(updateAt, LocalDateTime.now()).toMinutes() / 60.0);

        double recencyBoost = 1.0 + 0.5 * Math.exp(-ageHours / 24.0);
        double confidenceBoost = 0.8 + 0.4 * memory.getConfidence();
        double hitCountBoost = 1.0 + 0.1 * Math.log1p(Math.max(0, memory.getHitCount()));

        double typeBoost = switch (memory.getType()) {
            case USER -> 1.2;
            case GLOBAL -> 1.1;
            case PROJECT -> 1.0;
            case SESSION -> 0.9;
        };

        double base = sm.esScore() + 0.05 * sm.cosine();
        return base * recencyBoost * confidenceBoost * typeBoost * hitCountBoost;
    }

    private List<ScoredMemory> layeredSearch(String query, List<Float> vector, int topK) throws IOException {
        List<ScoredMemory> session = new ArrayList<>(hybridSearchByType(query, Math.min(topK, SESSION_MAX_RESULTS), vector, MemoryRecord.MemoryType.SESSION));
        Map<String, Double> cosineScores = vectorSearch(vector, topK * 2);

        double maxSessionCosine = session.stream()
                .mapToDouble(sm -> cosineScores.getOrDefault(sm.memory().getMemoryId(), 0.0))
                .max()
                .orElse(0.0);

        if (maxSessionCosine < SESSION_QUALITY_THRESHOLD) {
            log.debug("AUDIT_MEMORY_LAYER_SESSION_DROPPED sessionCandidates={} maxCosine={} threshold={}",
                    session.size(), num(maxSessionCosine), SESSION_QUALITY_THRESHOLD);
            session.clear();
        } else if (session.size() > SESSION_MAX_RESULTS) {
            session = new ArrayList<>(session.subList(0, SESSION_MAX_RESULTS));
        }

        List<ScoredMemory> result = new ArrayList<>(session);
        int remaining = topK - result.size();
        if (remaining <= 0) {
            return enrichWithCosine(result, cosineScores);
        }

        int base = remaining / 3;
        int extra = remaining % 3;
        int projectTopK = base + (extra > 0 ? 1 : 0);
        int globalTopK = base + (extra > 1 ? 1 : 0);
        int userTopK = base;

        log.debug("AUDIT_MEMORY_LAYER_QUOTA sessionKept={} maxSessionCosine={} projectTopK={} globalTopK={} userTopK={}",
                result.size(), num(maxSessionCosine), projectTopK, globalTopK, userTopK);

        if (projectTopK > 0) {
            result.addAll(hybridSearchByType(query, projectTopK, vector, MemoryRecord.MemoryType.PROJECT));
        }
        if (globalTopK > 0) {
            result.addAll(hybridSearchByType(query, globalTopK, vector, MemoryRecord.MemoryType.GLOBAL));
        }
        if (userTopK > 0) {
            result.addAll(hybridSearchByType(query, userTopK, vector, MemoryRecord.MemoryType.USER));
        }

        return enrichWithCosine(result, cosineScores);
    }

    private List<ScoredMemory> enrichWithCosine(List<ScoredMemory> memories, Map<String, Double> cosineScores) {
        List<ScoredMemory> enriched = new ArrayList<>();
        for (ScoredMemory sm : memories) {
            if (sm == null || sm.memory() == null || sm.memory().getMemoryId() == null) {
                continue;
            }
            double cosine = cosineScores.getOrDefault(sm.memory().getMemoryId(), 0.0);
            enriched.add(new ScoredMemory(sm.memory(), sm.score(), cosine));
        }
        return enriched;
    }

    private List<ScoredMemory> hybridSearchByType(String query, int topK, List<Float> vector, MemoryRecord.MemoryType type) throws IOException {
        if (topK <= 0) {
            return List.of();
        }

        int numCandidates = Math.max(100, topK * 10);
        // 关键：knn 子句【不继承】外层 query 的 filter，必须在 knn 上再挂一次 type 过滤。
        // 否则向量那一腿会跨类型召回，"分层召回 SESSION/PROJECT/GLOBAL/USER" 名存实亡
        // （实测：外层 filter(type=USER) 时混排结果里仍出现 PROJECT 记忆）。
        SearchResponse<MemoryRecord> response = esClient.search(s -> s
                        .index(INDEX_NAME)
                        .source(src -> src.filter(f -> f.excludes("content_vector")))
                        .query(buildQuery(query, type))
                        .knn(k -> {
                            var kb = k.field("content_vector")
                                    .queryVector(vector)
                                    .k(topK)
                                    .numCandidates(numCandidates);
                            if (type != null) {
                                kb.filter(q -> q.term(t -> t.field("type").value(type.name())));
                            }
                            return kb;
                        })
                        .rank(r -> r.rrf(rrf -> rrf.rankConstant(60L).rankWindowSize(100L)))
                        .size(topK),
                MemoryRecord.class
        );

        return response.hits().hits().stream()
                .filter(hit -> hit.source() != null && hit.source().getMemoryId() != null)
                .map(hit -> new ScoredMemory(hit.source(), hit.score()))
                .toList();
    }

    private Map<String, Double> vectorSearch(List<Float> vector, int topK) throws IOException {
        if (vector == null || vector.isEmpty() || topK <= 0) {
            return Map.of();
        }

        int numCandidates = Math.max(100, topK * 10);
        SearchResponse<MemoryRecord> response = esClient.search(s -> s
                        .index(INDEX_NAME)
                        .source(src -> src.filter(f -> f.excludes("content_vector")))
                        .knn(k -> k
                                .field("content_vector")
                                .queryVector(vector)
                                .k(topK)
                                .numCandidates(numCandidates)
                        )
                        .size(topK),
                MemoryRecord.class
        );

        Map<String, Double> scores = new HashMap<>();
        for (Hit<MemoryRecord> hit : response.hits().hits()) {
            if (hit.source() != null && hit.source().getMemoryId() != null) {
                scores.put(hit.source().getMemoryId(), toCosine(hit.score()));
            }
        }
        return scores;
    }

    /**
     * 把 ES kNN 的 {@code _score} 还原成真实余弦相似度。
     * <p>
     * ES 对 {@code similarity: cosine} 的 dense_vector，kNN 返回的 _score 不是原始余弦，
     * 而是被映射到 [0,1] 的 {@code (1 + cosine) / 2}。直接当余弦用会让所有阈值语义整体上移：
     * 例如 {@code MATCH_THRESHOLD = 0.85} 实际等价于真实余弦 0.70。
     * <p>
     * 实测对照（qwen3.7-text-embedding-flash）：离线算得真实余弦 0.6416 的一条文本对，
     * ES _score 返回 0.817 ≈ (1 + 0.6416) / 2，与映射公式一致。
     * <p>
     * 本方法是余弦语义的唯一出口，tryHit 阈值、SESSION 层质量门槛、业务重排公式
     * 因此统一工作在文档所声明的真实余弦上。int8_hnsw 量化带来的零点几偏差属正常噪声。
     */
    private static double toCosine(Double esScore) {
        if (esScore == null) {
            return 0.0;
        }
        return Math.max(-1.0, Math.min(1.0, 2.0 * esScore - 1.0));
    }

    private Query buildQuery(String query, MemoryRecord.MemoryType type) {
        if (type == null) {
            return Query.of(q -> q.match(m -> m.field("content").query(query)));
        }
        return Query.of(q -> q.bool(b -> b
                .must(m -> m.match(mm -> mm.field("content").query(query)))
                .filter(f -> f.term(t -> t.field("type").value(type.name())))
        ));
    }

    /**
     * 写入 ES 文档。返回 ES 侧的写入结果（CREATED / UPDATED），供审计日志区分新建与覆盖。
     */
    private String indexMemory(MemoryRecord memory, List<Float> vector) throws IOException {
        Map<String, Object> doc = new HashMap<>();
        doc.put("memoryId", memory.getMemoryId());
        doc.put("type", memory.getType().name());
        doc.put("content", memory.getContent());
        doc.put("confidence", memory.getConfidence());
        doc.put("updateAt", memory.getUpdateAt() == null ? LocalDateTime.now().toString() : memory.getUpdateAt().toString());
        doc.put("ttl", memory.getTtl());
        doc.put("hitCount", memory.getHitCount());
        doc.put("meta", memory.getMeta() == null ? Map.of() : memory.getMeta());
        doc.put("content_vector", vector);

        var response = esClient.index(i -> i
                .index(INDEX_NAME)
                .id(memory.getMemoryId())
                .document(doc)
        );
        return response.result().jsonValue();
    }

    private void ensureIndex() {
        if (indexInitialized) {
            return;
        }
        synchronized (this) {
            if (indexInitialized) {
                return;
            }
            try {
                boolean exists = esClient.indices().exists(e -> e.index(INDEX_NAME)).value();
                if (!exists) {
                    int dimensions = embeddingDimensions();
                    esClient.indices().create(c -> c
                            .index(INDEX_NAME)
                            .mappings(m -> m
                                    .properties("memoryId", p -> p.keyword(k -> k))
                                    .properties("content", p -> p.text(t -> t))
                                    .properties("content_vector", p -> p.denseVector(d -> d
                                            .dims(dimensions)
                                            .index(true)
                                            .similarity(DenseVectorSimilarity.Cosine)
                                    ))
                                    .properties("type", p -> p.keyword(k -> k))
                                    .properties("confidence", p -> p.float_(f -> f))
                                    .properties("updateAt", p -> p.date(d -> d))
                                    .properties("ttl", p -> p.integer(i -> i))
                                    .properties("hitCount", p -> p.integer(i -> i))
                                    .properties("meta", p -> p.object(o -> o.enabled(true)))
                            )
                    );
                    log.info("AUDIT_MEMORY_INDEX_CREATED index={} dims={} similarity=cosine", INDEX_NAME, dimensions);
                } else {
                    // 不在此处再探测维度：dimensions() 可能触发一次真实 embedding 调用。
                    log.info("AUDIT_MEMORY_INDEX_READY index={}", INDEX_NAME);
                }
                indexInitialized = true;
            } catch (IOException e) {
                log.warn("AUDIT_MEMORY_INDEX_INIT_FAILED index={} error={}", INDEX_NAME, e.getMessage(), e);
                throw new RuntimeException("Failed to initialize memory index", e);
            }
        }
    }

    private int embeddingDimensions() {
        try {
            int dimensions = embeddingModel.dimensions();
            if (dimensions > 0) {
                log.info("AUDIT_MEMORY_EMBEDDING_READY model={} dims={}",
                        embeddingModel.getClass().getSimpleName(), dimensions);
                return dimensions;
            }
            // 维度探测失败会回退到 1536，若真实模型是 1024 维则后续 kNN 必然失败，这里显式留一行审计。
            log.warn("AUDIT_MEMORY_EMBEDDING_DIMS_UNKNOWN probed={} fallback={}", dimensions, DEFAULT_EMBEDDING_DIMENSIONS);
            return DEFAULT_EMBEDDING_DIMENSIONS;
        } catch (Exception e) {
            log.warn("AUDIT_MEMORY_EMBEDDING_DIMS_PROBE_FAILED fallback={} error={}",
                    DEFAULT_EMBEDDING_DIMENSIONS, e.getMessage(), e);
            return DEFAULT_EMBEDDING_DIMENSIONS;
        }
    }

    private List<Float> embed(String text) {
        long start = System.nanoTime();
        try {
            float[] values = embeddingModel.embed(text);
            if (values == null || values.length == 0) {
                log.warn("AUDIT_MEMORY_EMBED_EMPTY query=\"{}\" durationMs={}", brief(text),
                        (System.nanoTime() - start) / 1_000_000);
                return List.of();
            }
            if (log.isDebugEnabled()) {
                log.debug("AUDIT_MEMORY_EMBED query=\"{}\" dims={} durationMs={}", brief(text), values.length,
                        (System.nanoTime() - start) / 1_000_000);
            }
            List<Float> vector = new ArrayList<>(values.length);
            for (float value : values) {
                vector.add(value);
            }
            return vector;
        } catch (Exception e) {
            log.warn("AUDIT_MEMORY_EMBED_FAILED query=\"{}\" durationMs={} error={}", brief(text),
                    (System.nanoTime() - start) / 1_000_000, e.getMessage(), e);
            throw e;
        }
    }

    private MemoryRecord buildRecord(String content, String runId, MemoryRecord.MemoryType type) {
        return MemoryRecord.builder()
                .memoryId(UUID.randomUUID().toString())
                .type(type)
                .content(content)
                .confidence(0.6d)
                .updateAt(LocalDateTime.now())
                .ttl(defaultTtl(type))
                .hitCount(0)
                .meta(new HashMap<>(Map.of("runId", runId == null ? "" : runId)))
                .build();
    }

    private MemoryRecord toMemoryRecord(RawMemories.RawMemory rawMemory, String runId, String action) {
        MemoryRecord.MemoryType type = parseMemoryType(rawMemory.getType());
        String memoryId = ACTION_UPDATE.equals(action) && notBlank(rawMemory.getExistingMemoryId())
                ? rawMemory.getExistingMemoryId()
                : UUID.randomUUID().toString();

        double confidence = rawMemory.getConfidence() == null ? 0.6d : clamp01(rawMemory.getConfidence(), 0.6d);
        int ttl = rawMemory.getTtlSeconds() == null || rawMemory.getTtlSeconds() <= 0
                ? defaultTtl(type)
                : rawMemory.getTtlSeconds();

        Map<String, Object> meta = new HashMap<>();
        meta.put("runId", runId == null ? "" : runId);
        meta.put("action", action);
        meta.put("scope", rawMemory.getScope());
        meta.put("importance", rawMemory.getImportance() == null ? 0.5d : clamp01(rawMemory.getImportance(), 0.5d));
        if (notBlank(rawMemory.getDedupeKey())) {
            meta.put("dedupeKey", rawMemory.getDedupeKey());
        }
        if (rawMemory.getTags() != null) {
            meta.put("tags", rawMemory.getTags());
        }
        if (notBlank(rawMemory.getReason())) {
            meta.put("reason", rawMemory.getReason());
        }
        rememberHit(meta, runId);

        return MemoryRecord.builder()
                .memoryId(memoryId)
                .type(type)
                .content(rawMemory.getContent())
                .confidence(confidence)
                .updateAt(LocalDateTime.now())
                .ttl(ttl)
                .hitCount(0)
                .meta(meta)
                .build();
    }

    /**
     * 抽取记忆：失败自动重试。
     * <p>
     * 真实 chat 模型偶发超时后，图节点会把异常文本当成模型回答返回（实测 payload 以 Exception 开头），
     * 旧实现一次失败就丢掉整轮记忆，而且丢失方式有两种：解析抛异常，以及「输出里没有 JSON」被当成
     * 无可抽取内容静默返回 extracted=0。大测试集实测 3/8 个声明批次因此全灭。
     * <p>
     * 现在把「调用异常 / 输出不可解析 / 无 JSON 负载 / 空响应」统一视为可重试，最多
     * EXTRACT_MAX_ATTEMPTS 次并退避；确定性错误（prompt 构建失败）不重试。
     * 全部尝试仍失败时抛给 save() 按既有语义吞掉，但会留下 AUDIT_MEMORY_EXTRACT_GIVEUP，
     * 不再伪装成「本轮没有值得记的内容」。
     */
    private RawMemories extractMemory(List<Message> messages, String runId) {
        RuntimeException lastError = null;
        for (int attempt = 1; attempt <= EXTRACT_MAX_ATTEMPTS; attempt++) {
            long start = System.nanoTime();
            try {
                RawMemories rawMemories = extractOnce(messages, runId);
                log.info("AUDIT_MEMORY_EXTRACT_AGENT runId={} memories={} attempt={}/{} durationMs={}",
                        runId, rawMemories.getMemories().size(), attempt, EXTRACT_MAX_ATTEMPTS,
                        (System.nanoTime() - start) / 1_000_000);
                return rawMemories;
            } catch (JsonProcessingException e) {
                // prompt 构建失败是确定性错误，重试没有意义
                log.warn("AUDIT_MEMORY_EXTRACT_FAILED runId={} stage=prompt error={}", runId, e.getMessage(), e);
                throw new RuntimeException("Failed to build memory extraction prompt", e);
            } catch (IOException e) {
                log.warn("AUDIT_MEMORY_EXTRACT_FAILED runId={} stage=existingMemories attempt={}/{} retryable=true error={}",
                        runId, attempt, EXTRACT_MAX_ATTEMPTS, e.getMessage(), e);
                lastError = new RuntimeException("Failed to collect existing memories", e);
            } catch (RetryableExtractionException e) {
                log.warn("AUDIT_MEMORY_EXTRACT_FAILED runId={} stage=agent attempt={}/{} durationMs={} retryable=true error={}",
                        runId, attempt, EXTRACT_MAX_ATTEMPTS, (System.nanoTime() - start) / 1_000_000, e.getMessage());
                lastError = e;
            }
            if (attempt < EXTRACT_MAX_ATTEMPTS) {
                long backoff = EXTRACT_BACKOFF_MS[Math.min(attempt - 1, EXTRACT_BACKOFF_MS.length - 1)];
                log.info("AUDIT_MEMORY_EXTRACT_RETRY runId={} nextAttempt={}/{} backoffMs={}",
                        runId, attempt + 1, EXTRACT_MAX_ATTEMPTS, backoff);
                sleepBeforeRetry(backoff);
            }
        }
        log.warn("AUDIT_MEMORY_EXTRACT_GIVEUP runId={} attempts={} lastError={}",
                runId, EXTRACT_MAX_ATTEMPTS, lastError == null ? "unknown" : String.valueOf(lastError.getMessage()));
        throw lastError != null ? lastError : new RuntimeException("Memory extraction failed");
    }

    /** 单次抽取：调用抽取 Agent 并解析输出。可重试的失败统一抛 RetryableExtractionException。 */
    private RawMemories extractOnce(List<Message> messages, String runId)
            throws JsonProcessingException, IOException {
        RunnableConfig runnableConfig = RunnableConfig.builder()
                .threadId((runId == null ? "" : runId) + "_MEMORY")
                .build();

        UserMessage userMessage = new UserMessage(buildMemoryPrompt(messages, runId));
        AssistantMessage call;
        try {
            call = lazyMemoryAgent().call(userMessage, runnableConfig);
        } catch (GraphRunnerException e) {
            throw new RetryableExtractionException("agent call failed: " + e.getMessage(), e);
        }
        if (log.isDebugEnabled()) {
            // 抽取 Agent 的原始输出：定位「为什么没记住 / 为什么记错」时最关键的一行。
            log.debug("AUDIT_MEMORY_EXTRACT_RAW runId={} payload={}", runId, brief(call.getText()));
        }
        return parseRawMemories(call.getText(), runId);
    }

    private static void sleepBeforeRetry(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RetryableExtractionException("interrupted while backing off before retry", e);
        }
    }

    /** 抽取阶段可重试的失败：模型调用异常、输出不可解析、无 JSON 负载、空响应。 */
    private static class RetryableExtractionException extends RuntimeException {
        RetryableExtractionException(String message) {
            super(message);
        }

        RetryableExtractionException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private String buildMemoryPrompt(List<Message> messages, String runId) throws JsonProcessingException, IOException {
        List<Map<String, Object>> messagePayloads = new ArrayList<>();
        for (Message message : messages) {
            if (message == null) {
                continue;
            }
            Map<String, Object> payload = new HashMap<>();
            payload.put("type", message.getMessageType().getValue());
            payload.put("text", message.getText() == null ? "" : message.getText());
            messagePayloads.add(payload);
        }

        List<Map<String, Object>> existingMemories = collectExistingMemories(messages);

        Map<String, Object> request = new HashMap<>();
        request.put("runId", runId);
        request.put("messages", messagePayloads);
        request.put("existingMemories", existingMemories);

        String messagesJson = objectMapper.writeValueAsString(request);
        return PromptConfig.MEMORY_USER.replace("{messagesJson}", messagesJson);
    }

    private List<Map<String, Object>> collectExistingMemories(List<Message> messages) throws IOException {
        Map<String, MemoryRecord> unique = new LinkedHashMap<>();
        for (Message message : messages) {
            if (message == null || message.getText() == null || message.getText().isBlank()) {
                continue;
            }
            List<Float> vector = embed(message.getText());
            if (vector.isEmpty()) {
                continue;
            }
            List<ScoredMemory> hits = hybridSearchByType(message.getText(), 5, vector, null);
            for (ScoredMemory hit : hits) {
                if (hit.memory() != null && hit.memory().getMemoryId() != null && !isExpired(hit.memory())) {
                    unique.putIfAbsent(hit.memory().getMemoryId(), hit.memory());
                }
            }
            if (unique.size() >= 20) {
                break;
            }
        }

        List<Map<String, Object>> payload = unique.values().stream()
                .map(this::memoryToPromptPayload)
                .toList();
        // 喂给抽取 Agent 的“旧记忆上下文”直接决定它输出 ADD 还是 UPDATE，排障时必须能看到。
        log.debug("AUDIT_MEMORY_EXTRACT_CONTEXT existing={}", payload.size());
        return payload;
    }

    private Map<String, Object> memoryToPromptPayload(MemoryRecord memory) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("memoryId", memory.getMemoryId());
        payload.put("type", memory.getType() == null ? null : memory.getType().name());
        payload.put("content", memory.getContent());
        payload.put("confidence", memory.getConfidence());
        payload.put("updateAt", memory.getUpdateAt() == null ? null : memory.getUpdateAt().toString());
        payload.put("ttl", memory.getTtl());
        payload.put("hitCount", memory.getHitCount());
        if (memory.getMeta() != null) {
            payload.put("scope", memory.getMeta().get("scope"));
            payload.put("dedupeKey", memory.getMeta().get("dedupeKey"));
            payload.put("tags", memory.getMeta().get("tags"));
        }
        return payload;
    }

    /**
     * 解析抽取 Agent 的输出。
     * <p>
     * 关键区分：{@code {"memories":[]}} 才是「本轮确实没有值得记的内容」（正常结果，不重试）；
     * 空响应、无 JSON、解析失败、字面量 null 都说明上游出了问题，按可重试失败抛出。
     */
    private RawMemories parseRawMemories(String text, String runId) throws JsonProcessingException {
        if (text == null || text.isBlank()) {
            throw new RetryableExtractionException("blank response");
        }
        String json = extractJsonObject(text);
        if (json.isEmpty()) {
            throw new RetryableExtractionException("no json payload, response=" + brief(text));
        }

        RawMemories memories;
        try {
            memories = objectMapper.readValue(json, RawMemories.class);
        } catch (JsonProcessingException e) {
            log.warn("AUDIT_MEMORY_EXTRACT_PARSE_FAILED runId={} error={} payload={}",
                    runId, e.getOriginalMessage(), brief(json));
            throw new RetryableExtractionException("unparsable payload: " + e.getOriginalMessage(), e);
        }
        if (memories == null) {
            // 模型输出字面量 null 不是「无可抽取记忆」，而是上游异常输出，交给重试
            throw new RetryableExtractionException("literal null payload");
        }
        if (memories.getMemories() == null) {
            memories.setMemories(List.of());
        }
        return memories;
    }

    private String extractJsonObject(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }

        String cleaned = text.trim();
        if (cleaned.startsWith("```")) {
            int firstLineEnd = cleaned.indexOf('\n');
            if (firstLineEnd >= 0) {
                cleaned = cleaned.substring(firstLineEnd + 1);
            }
            int fence = cleaned.lastIndexOf("```");
            if (fence >= 0) {
                cleaned = cleaned.substring(0, fence);
            }
            cleaned = cleaned.trim();
        }

        int start = cleaned.indexOf('{');
        int end = cleaned.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return cleaned.substring(start, end + 1);
        }
        return cleaned;
    }

    private int defaultTtl(MemoryRecord.MemoryType type) {
        return switch (type) {
            case USER -> 365 * 24 * 60 * 60;
            case GLOBAL -> 90 * 24 * 60 * 60;
            case PROJECT -> 30 * 24 * 60 * 60;
            case SESSION -> 24 * 60 * 60;
        };
    }

    private MemoryRecord.MemoryType parseMemoryType(String type) {
        if (type == null || type.isBlank()) {
            return MemoryRecord.MemoryType.SESSION;
        }
        try {
            return MemoryRecord.MemoryType.valueOf(type.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            String normalized = type.trim().toUpperCase(Locale.ROOT);
            if (normalized.contains("SESSION")) {
                return MemoryRecord.MemoryType.SESSION;
            }
            if (normalized.contains("PROJECT")) {
                return MemoryRecord.MemoryType.PROJECT;
            }
            if (normalized.contains("GLOBAL")) {
                return MemoryRecord.MemoryType.GLOBAL;
            }
            if (normalized.contains("USER")) {
                return MemoryRecord.MemoryType.USER;
            }
            return MemoryRecord.MemoryType.SESSION;
        }
    }

    private String normalizeAction(String action) {
        if (action == null || action.isBlank()) {
            return ACTION_ADD;
        }
        String normalized = action.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "ADD", "CREATE", "INSERT" -> ACTION_ADD;
            case "UPDATE", "MERGE", "OVERWRITE" -> ACTION_UPDATE;
            case "DELETE", "REMOVE", "FORGET" -> ACTION_DELETE;
            case "NONE", "SKIP", "IGNORE" -> ACTION_NONE;
            default -> ACTION_ADD;
        };
    }

    private double clamp01(Double value, double fallback) {
        if (value == null || value.isNaN()) {
            return fallback;
        }
        return Math.max(0.0d, Math.min(1.0d, value));
    }

    private boolean compatible(MemoryRecord.MemoryType rawType, MemoryRecord.MemoryType existingType) {
        // 第一版只做同类型命中，避免“项目事实”误强化成“用户偏好”。
        return rawType == existingType;
    }

    private boolean isExpired(MemoryRecord memory) {
        if (memory == null || memory.getTtl() <= 0 || memory.getUpdateAt() == null) {
            return false;
        }
        return memory.getUpdateAt().plusSeconds(memory.getTtl()).isBefore(LocalDateTime.now());
    }

    private boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private void rememberHit(Map<String, Object> meta, Object runId) {
        if (runId == null || String.valueOf(runId).isBlank()) {
            return;
        }
        Object existing = meta.get("hitRunIds");
        Set<String> ids = new HashSet<>();
        if (existing instanceof Collection<?> collection) {
            for (Object item : collection) {
                if (item != null) {
                    ids.add(String.valueOf(item));
                }
            }
        }
        ids.add(String.valueOf(runId));
        meta.put("hitRunIds", ids);
        meta.put("distinctHitCount", ids.size());
    }

    private Map<String, Object> mergeMeta(Map<String, Object> oldMeta, Map<String, Object> newMeta) {
        Map<String, Object> merged = new HashMap<>();
        if (oldMeta != null) {
            merged.putAll(oldMeta);
        }
        if (newMeta != null) {
            merged.putAll(newMeta);
        }
        return merged;
    }

    private int intValue(Object value, int defaultValue) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    private void promoteType(MemoryRecord target, MemoryRecord.MemoryType newType) {
        MemoryRecord.MemoryType oldType = target.getType();
        target.setType(newType);
        target.setTtl(defaultTtl(newType));
        log.info("AUDIT_MEMORY_PROMOTE memoryId={} from={} to={} ttl={} distinctHits={}",
                target.getMemoryId(), oldType, newType, target.getTtl(),
                target.getMeta() == null ? null : target.getMeta().get("distinctHitCount"));
    }

    // ==================== 审计日志辅助 ====================

    /** 压掉换行并按默认长度截断，保证一条审计记录只占一行、便于 grep。 */
    private String brief(String text) {
        return brief(text, AUDIT_CONTENT_MAX_LENGTH);
    }

    private String brief(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        String oneLine = text.replaceAll("\\s+", " ").trim();
        if (oneLine.length() <= maxLength) {
            return oneLine;
        }
        return oneLine.substring(0, maxLength) + "...(len=" + oneLine.length() + ")";
    }

    /** 浮点统一保留 3 位小数，避免审计日志里出现 0.6999999999 这类难以比对的值。 */
    private double num(double value) {
        return Math.round(value * 1000d) / 1000d;
    }

    /** 取 memoryId 前 8 位：完整 UUID 出现在每一行会淹没有效信息。 */
    private String shortId(String memoryId) {
        if (memoryId == null || memoryId.isBlank()) {
            return "-";
        }
        return memoryId.substring(0, Math.min(8, memoryId.length()));
    }

    private String metaValue(MemoryRecord memory, String key) {
        if (memory == null || memory.getMeta() == null) {
            return null;
        }
        Object value = memory.getMeta().get(key);
        return value == null ? null : String.valueOf(value);
    }

    /** 召回结果采样，形如 [USER:a1b2c3d4:用户偏好X, SESSION:e5f6a7b8:本次任务Y]。 */
    private String summarize(List<MemoryRecord> records) {
        if (records == null || records.isEmpty()) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        int limit = Math.min(records.size(), AUDIT_HIT_SAMPLE);
        for (int i = 0; i < limit; i++) {
            MemoryRecord record = records.get(i);
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(record.getType()).append(':').append(shortId(record.getMemoryId()))
                    .append(':').append(brief(record.getContent(), AUDIT_HIT_CONTENT_MAX_LENGTH));
        }
        if (records.size() > limit) {
            sb.append(", ...").append(records.size() - limit).append("more");
        }
        return sb.append(']').toString();
    }

    private Map<String, Long> countByType(List<MemoryRecord> records) {
        if (records == null || records.isEmpty()) {
            return Map.of();
        }
        Map<String, Long> counts = new LinkedHashMap<>();
        for (MemoryRecord record : records) {
            counts.merge(String.valueOf(record.getType()), 1L, Long::sum);
        }
        return counts;
    }
}