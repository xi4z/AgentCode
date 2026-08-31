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

    private static final double MATCH_THRESHOLD = 0.85;
    private static final double SESSION_QUALITY_THRESHOLD = 0.72;

    private static final String ACTION_ADD = "ADD";
    private static final String ACTION_UPDATE = "UPDATE";
    private static final String ACTION_DELETE = "DELETE";
    private static final String ACTION_NONE = "NONE";

    private final ReactAgent memoryAgent;
    private final EmbeddingModel embeddingModel;
    private final ElasticsearchClient esClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private volatile boolean indexInitialized = false;

    public HybridMemoryStore(ChatModel chatModel,
                             EmbeddingModel embeddingModel,
                             ElasticsearchClient esClient) {
        this.embeddingModel = embeddingModel;
        this.esClient = esClient;
        this.memoryAgent = ReactAgent.builder()
                .name("memory_agent")
                .description("一个用于分析输入对话中有什么值得记入长期记忆中的Agent")
                .model(chatModel)
                .systemPrompt(PromptConfig.MEMORY_PROMPT)
                .outputType(RawMemories.class)
                .build();
    }

    @Override
    public void save(List<Message> messages, String runId) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        ensureIndex();

        try {
            RawMemories memories = extractMemory(messages, runId);
            if (memories == null || memories.getMemories() == null) {
                return;
            }

            for (RawMemories.RawMemory rawMemory : memories.getMemories()) {
                applyRawMemory(rawMemory, runId);
            }
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

    private void applyRawMemory(RawMemories.RawMemory rawMemory, String runId) {
        if (rawMemory == null) {
            return;
        }

        String action = normalizeAction(rawMemory.getAction());

        if (ACTION_NONE.equals(action)) {
            return;
        }

        if (ACTION_DELETE.equals(action)) {
            if (notBlank(rawMemory.getExistingMemoryId())) {
                deleteMemory(rawMemory.getExistingMemoryId());
            }
            return;
        }

        if (!notBlank(rawMemory.getContent())) {
            return;
        }

        MemoryRecord candidate = toMemoryRecord(rawMemory, runId, action);
        List<Float> vector = embed(candidate.getContent());
        if (vector.isEmpty()) {
            return;
        }

        if (ACTION_UPDATE.equals(action) && notBlank(rawMemory.getExistingMemoryId())) {
            updateExistingMemory(candidate, rawMemory.getExistingMemoryId(), vector);
            return;
        }

        saveInternal(candidate, vector);
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

    private void updateExistingMemory(MemoryRecord candidate, String existingMemoryId, List<Float> vector) {
        MemoryRecord existing = getMemory(existingMemoryId);
        if (existing == null) {
            candidate.setMemoryId(existingMemoryId);
            try {
                indexMemory(candidate, vector);
            } catch (IOException e) {
                throw new RuntimeException("Update agent memory failed", e);
            }
            return;
        }

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

        try {
            // content_vector 已变化，使用全量 index 覆盖，避免旧向量残留。
            indexMemory(existing, vector);
        } catch (IOException e) {
            throw new RuntimeException("Update agent memory failed", e);
        }
    }

    private void deleteMemory(String memoryId) {
        try {
            esClient.delete(d -> d.index(INDEX_NAME).id(memoryId));
        } catch (IOException e) {
            throw new RuntimeException("Delete agent memory failed", e);
        }
    }

    private MemoryRecord getMemory(String memoryId) {
        try {
            return esClient.get(g -> g.index(INDEX_NAME).id(memoryId), MemoryRecord.class).source();
        } catch (IOException e) {
            throw new RuntimeException("Get agent memory failed", e);
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
        meta.put("lastMatchContent", rawMemory.getContent());
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

    private RawMemories extractMemory(List<Message> messages, String runId) {
        try {
            RunnableConfig runnableConfig = RunnableConfig.builder()
                    .threadId((runId == null ? "" : runId) + "_MEMORY")
                    .build();

            UserMessage userMessage = new UserMessage(buildMemoryPrompt(messages, runId));
            AssistantMessage call = memoryAgent.call(userMessage, runnableConfig);
            return parseRawMemories(call.getText());
        } catch (GraphRunnerException e) {
            throw new RuntimeException(e);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to build memory extraction prompt", e);
        } catch (IOException e) {
            throw new RuntimeException("Failed to collect existing memories", e);
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

        return unique.values().stream()
                .map(this::memoryToPromptPayload)
                .toList();
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

    private RawMemories parseRawMemories(String text) throws JsonProcessingException {
        String json = extractJsonObject(text);
        if (json.isEmpty()) {
            return new RawMemories(List.of());
        }

        RawMemories memories = objectMapper.readValue(json, RawMemories.class);
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
        target.setType(newType);
        target.setTtl(defaultTtl(newType));
    }
}