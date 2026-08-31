package com.agentcode.memory;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.ml.get_memory_stats.Memory;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class HybridMemoryStore implements MemoryStore {

    private final ReactAgent memoryAgent;
    private final EmbeddingModel embeddingModel;
    private final ElasticsearchClient esClient;

    @Override
    public void save(List<Message> messages, String runId) {
        // 1. 启动 Agent 对当前已知信息检查并返回数个值得记录的消息

        // 这里启动 for-each 循环

        // 这里使用 emb 将记忆转成向量

        // save 时, 先进行一次搜索相关记忆, 如有就增强

        // 没有就尝试插入


    }

    @Override
    public List<MemoryRecord> search(String content) {

        /*
            TODO 搜索条例
                从下至上开始搜, 即从 Session -> Global / User 开始搜
         */
        return null;
    }

    /**
     * 真正的存记忆方法
     * @param memory 需要存储的记忆
     * @param runId 会话ID
     * @param memoryType 记忆类型, 到指定阈值后可以升级
     */
    private void save(String memory, String runId, MemoryRecord.MemoryType memoryType) {

    }

    /**
     * 对记忆进行升级或其他
     * @param memory
     */
    private void updateMemory(MemoryRecord memory){


    }

    private void strengthenMemory(MemoryRecord rawMemory, String targetMemoryId) throws IOException {
        MemoryRecord targetMemory = esClient.get(s -> s
                        .index("agent_memory")
                        .id(targetMemoryId),
                MemoryRecord.class
        ).source();
        /*
        TODO 检查 targetMemory 的增强情况
             注意一下情况
             1. rawMemory 的 type 超过 targetMemoryId 时, targetMemory 的 type 继承自 rawMemory, hitCount + 1
             2. raw 和 target 同属一个 proj, 则升级到 proj 等级, 但是 TTL 较短
         */



    }
    /**
     * 尝试命中一次已经有过的记忆
     * @param memory
     * @return
     */
    private boolean tryHit(MemoryRecord memory, List<Float> vector){
        // TODO: 查找相似记忆并执行命中强化；暂未实现，先返回 false 表示未命中
        try {
            List<ScoredMemory> rrf = this.rrfSearch(memory.getContent(), 10, vector);
            if (rrf.isEmpty()){
                return false;
            }
            Map<String, Double> knn = vectorSearch(vector, 10);
            for (ScoredMemory scoredMemory : rrf) {
                // 检查待选记忆中有没有特别匹配的
                MemoryRecord memo = scoredMemory.memory();
                Double similarity = knn.getOrDefault(memo.memoryId, 0.0);
                if (similarity >= 0.85){
                    // TODO 这里需要对拿到的记忆进行增强
                }


            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return false;
    }

    /**
     * 对初步rerank后的 memories 再按如下字段重新排序
     * hitCount, confidence, updateAt
     * @param memories
     * @return
     */
    private List<MemoryRecord> reRank(List<ScoredMemory> memories){
        return memories.stream()
                .sorted(Comparator.comparingDouble(sm -> -businessScore(sm)))
                .map(ScoredMemory::memory)
                .toList();


    }
    private double businessScore(ScoredMemory sm) {
        MemoryRecord memory = sm.memory();

        double ageHours = Duration.between(memory.getUpdateAt(), LocalDateTime.now()).toHours();

        double recencyBoost = 1 + 0.5 * Math.exp(-ageHours / 24.0);
        double confidenceBoost = 0.8 + 0.4 * memory.getConfidence();

        double sourceBoost = switch (memory.getType()) {
            case USER -> 1.2;
            case GLOBAL -> 1.1;
            case PROJECT -> 1.0;
            case SESSION -> 0.9;
        };

        return sm.score()
                * recencyBoost
                * confidenceBoost
                * sourceBoost;
    }
    private List<ScoredMemory> rrfSearch(String query, int topK, List<Float> vector) throws IOException {
        SearchResponse<MemoryRecord> response = esClient.search(s -> s
                        .index("agent_memory")
                        // 倒排
                        .query(q -> q
                                .match(m -> m
                                        .field("content")
                                        .query(query)
                                )
                        )
                        // 向量
                        .knn(k -> k
                                .field("content_vector")
                                .queryVector(vector)
                                .k(topK)
                                .numCandidates(100)
                        )
                        // RRF 融合
                        .rank(r -> r
                                .rrf(rrf -> rrf
                                        .rankConstant(60L)
                                        .rankWindowSize(100L)
                                )
                        )
                        .size(topK),
                MemoryRecord.class
        );

        return response.hits().hits().stream()
                .map(memoryRecordHit -> new ScoredMemory(memoryRecordHit.source(), memoryRecordHit.score()))
                .filter(scoredMemory -> {
                    return scoredMemory.memory() != null;
                })
                .toList();
    }
    private Map<String, Double> vectorSearch(List<Float> vector, int topK) throws IOException {
        SearchResponse<MemoryRecord> response = esClient.search(s -> s
                        .index("agent_memory")
                        .source(src -> src.filter(f -> f.excludes("content_vector")))
                        .knn(k -> k
                                .field("content_vector")
                                .queryVector(vector)
                                .k(topK)
                                .numCandidates(topK * 10)
                        )
                        .size(topK),
                MemoryRecord.class
        );

        Map<String, Double> scores = new HashMap<>();

        for (Hit<MemoryRecord> hit : response.hits().hits()) {
            if (hit.source() != null) {
                scores.put(hit.source().memoryId, hit.score());
            }
        }

        return scores;
    }


}
