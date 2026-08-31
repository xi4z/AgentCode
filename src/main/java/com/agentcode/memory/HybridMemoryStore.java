package com.agentcode.memory;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.mapping.DenseVectorSimilarity;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
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
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 基于 Elasticsearch 的长期记忆实现。
 *
 * <p>当前策略：
 * <ul>
 *   <li>save：先用规则抽取候选记忆，再用向量 + 倒排混排找旧记忆；命中则强化，未命中则新建</li>
 *   <li>tryHit：只尝试命中同类型记忆，并用纯向量 cosine 相似度判断是否同一条</li>
 *   <li>search：分层召回 SESSION / PROJECT / GLOBAL / USER，再按业务分数重排</li>
 *   <li>strengthen：提升 hitCount / confidence / TTL，并满足阈值后逐步升级记忆类型</li>
 * </ul>
 *
 * <p>后续可把 {@link #extractMemory(Message, String)} 替换为 ReActAgent / ChatClient 结构化输出，
 * 以更接近 Mem0、Letta 等系统的“LLM 抽取 + 记忆更新”模式。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HybridMemoryStore implements MemoryStore {

    private static final String INDEX_NAME = "agent_memory";
    private static final int DEFAULT_EMBEDDING_DIMENSIONS = 1536;
    private static final int DEFAULT_SEARCH_TOP_K = 20;
    private static final int SESSION_MAX_RESULTS = 8;
    private static final int HIT_TOP_K = 10;
    private static final int COSINE_TOP_K = 20;

    private static final double MATCH_THRESHOLD = 0.85;
    private static final double SESSION_QUALITY_THRESHOLD = 0.72;

    private final EmbeddingModel embeddingModel;
    private final ElasticsearchClient esClient;

    private volatile boolean indexInitialized = false;

    @Override
    public void save(List<Message> messages, String runId) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        ensureIndex();

        for (Message message : messages) {
            String content = extractMemory(message, runId);
            if (content == null || content.isBlank()) {
                continue;
            }

            MemoryRecord candidate = buildRecord(content, runId, classifyMemoryType(content));
            List<Float> vector = embed(candidate.getContent());
            if (vector.isEmpty()) {
                continue;
            }

            saveInternal(candidate, vector);
        }
    }

    @Override
    public List<MemoryRecord> search(String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        ensureIndex();

        List<Float> vector = embed(content);
        if (vector.isEmpty()) {
            return List.of();
        }

        try {
            List<ScoredMemory> candidates = layeredSearch(content, vector, DEFAULT_SEARCH_TOP_K);
            return reRank(candidates);
        } catch (IOException e) {
            throw new RuntimeException("Search agent memory failed", e);
        }
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
            indexMemory(candidate, vector);
        } catch (IOException e) {
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
            throw new RuntimeException("Update agent memory failed", e);
        }
    }

    private void strengthenMemory(MemoryRecord rawMemory, MemoryRecord target) {
        if (target == null) {
            return;
        }

        target.setHitCount(target.getHitCount() + 1);

        double increment = Math.max(0.02d, 0.08d * rawMemory.getConfidence());
        target.setConfidence(Math.min(1.0d, target.getConfidence() + increment));
        target.setUpdateAt(LocalDateTime.now());
        target.setTtl(defaultTtl(target.getType()));

        Map<String, Object> meta = target.getMeta() == null ? new HashMap<>() : new HashMap<>(target.getMeta());
        rememberHit(meta, rawMemory.getMeta() == null ? null : rawMemory.getMeta().get("runId"));
        target.setMeta(meta);

        // 基础升级策略：优先按“不同 runId 命中数”泛化，避免同一个会话内重复刷屏导致误升级。
        int distinctHits = intValue(meta.get("distinctHitCount"), target.getHitCount());
        if (target.getType() == MemoryRecord.MemoryType.SESSION && distinctHits >= 3) {
            promoteType(target, MemoryRecord.MemoryType.PROJECT);
        } else if (target.getType() == MemoryRecord.MemoryType.PROJECT && distinctHits >= 5) {
            promoteType(target, MemoryRecord.MemoryType.GLOBAL);
        } else if (target.getType() == MemoryRecord.MemoryType.GLOBAL && distinctHits >= 8) {
            promoteType(target, MemoryRecord.MemoryType.USER);
        }

        updateMemory(target);
    }

    /**
     * 尝试命中一次已有记忆。
     */
    private boolean tryHit(MemoryRecord memory, List<Float> vector) throws IOException {
        List<ScoredMemory> candidates = hybridSearchByType(memory.getContent(), HIT_TOP_K, vector, memory.getType());
        if (candidates.isEmpty()) {
            return false;
        }

        Map<String, Double> cosineScores = vectorSearch(vector, COSINE_TOP_K);
        for (ScoredMemory candidate : candidates) {
            MemoryRecord existing = candidate.memory();
            double cosine = cosineScores.getOrDefault(existing.getMemoryId(), 0.0);
            if (cosine >= MATCH_THRESHOLD && compatible(memory.getType(), existing.getType())) {
                strengthenMemory(memory, existing);
                return true;
            }
        }
        return false;
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

        double base = sm.score() + 0.05 * sm.cosineScore();
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
        SearchResponse<MemoryRecord> response = esClient.search(s -> s
                        .index(INDEX_NAME)
                        .source(src -> src.filter(f -> f.excludes("content_vector")))
                        .query(buildQuery(query, type))
                        .knn(k -> k
                                .field("content_vector")
                                .queryVector(vector)
                                .k(topK)
                                .numCandidates(numCandidates)
                        )
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
                scores.put(hit.source().getMemoryId(), hit.score());
            }
        }
        return scores;
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

    private void indexMemory(MemoryRecord memory, List<Float> vector) throws IOException {
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

        esClient.index(i -> i
                .index(INDEX_NAME)
                .id(memory.getMemoryId())
                .document(doc)
        );
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
                }
                indexInitialized = true;
            } catch (IOException e) {
                throw new RuntimeException("Failed to initialize memory index", e);
            }
        }
    }

    private int embeddingDimensions() {
        try {
            int dimensions = embeddingModel.dimensions();
            return dimensions > 0 ? dimensions : DEFAULT_EMBEDDING_DIMENSIONS;
        } catch (Exception e) {
            return DEFAULT_EMBEDDING_DIMENSIONS;
        }
    }

    private List<Float> embed(String text) {
        float[] values = embeddingModel.embed(text);
        if (values == null || values.length == 0) {
            return List.of();
        }
        List<Float> vector = new ArrayList<>(values.length);
        for (float value : values) {
            vector.add(value);
        }
        return vector;
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

    private int defaultTtl(MemoryRecord.MemoryType type) {
        return switch (type) {
            case USER -> 365 * 24 * 60 * 60;
            case GLOBAL -> 90 * 24 * 60 * 60;
            case PROJECT -> 30 * 24 * 60 * 60;
            case SESSION -> 24 * 60 * 60;
        };
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
        target.setType(newType);
        target.setTtl(defaultTtl(newType));
    }

    /**
     * 规则抽取候选记忆。
     *
     * <p>TODO: 这里可以替换为 ReActAgent / ChatClient 结构化输出，由模型判断：
     * 1. 是否值得长期保存；2. 保存类型；3. 是新增还是覆盖/合并；4. 是否需要摘要。
     */
    private String extractMemory(Message message, String runId) {
        if (message == null) {
            return null;
        }
        if (!(message instanceof UserMessage) && !(message instanceof AssistantMessage)) {
            return null;
        }
        if (message instanceof AssistantMessage assistantMessage && assistantMessage.hasToolCalls()) {
            return null;
        }

        String text = message.getText();
        if (text == null || text.isBlank()) {
            return null;
        }
        String trimmed = text.trim();
        if (trimmed.length() < 8) {
            return null;
        }

        return trimmed;
    }

    private MemoryRecord.MemoryType classifyMemoryType(String content) {
        String text = content.toLowerCase();
        if (containsAny(text, "以后都", "长期", "一直", "记住我", "我的偏好", "我喜欢", "我讨厌", "我不喜欢")) {
            return MemoryRecord.MemoryType.USER;
        }
        if (containsAny(text, "这个项目", "本仓库", "当前仓库", "项目约定", "项目用", "项目使用")) {
            return MemoryRecord.MemoryType.PROJECT;
        }
        if (containsAny(text, "全局", "所有项目", "通用", "跨项目")) {
            return MemoryRecord.MemoryType.GLOBAL;
        }
        return MemoryRecord.MemoryType.SESSION;
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private record ScoredMemory(
            MemoryRecord memory,
            double score,
            double cosineScore
    ) {
        ScoredMemory(MemoryRecord memory, double score) {
            this(memory, score, 0.0);
        }
    }
}