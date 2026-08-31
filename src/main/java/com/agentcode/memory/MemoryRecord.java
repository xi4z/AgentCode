package com.agentcode.memory;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;


import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;


/**
 * 长期记忆记录模型。
 *
 * <p>字段说明：
 * <ul>
 *   <li>memoryId：稳定且唯一的记忆 id，建议同时作为 Elasticsearch 文档 _id</li>
 *   <li>type：记忆类型/范围，SESSION -> PROJECT -> GLOBAL -> USER</li>
 *   <li>content：记忆正文</li>
 *   <li>confidence：记忆置信度，用于判断是否值得命中/合并</li>
 *   <li>updateAt：最近一次更新时间，用于时间衰减和“最近优先”</li>
 *   <li>ttl：有效时长（秒），按 updateAt 起算</li>
 *   <li>hitCount：累计命中次数，用于强化/升级</li>
 *   <li>meta：扩展元数据，例如 runId、projectId、sessionId、scope 等</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class MemoryRecord {

    private String memoryId;

    @Builder.Default
    private MemoryType type = MemoryType.SESSION;

    private String content;

    @Builder.Default
    private double confidence = 0.6d;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    @JsonSerialize(using = LocalDateTimeSerializer.class)
    @JsonDeserialize(using = LocalDateTimeDeserializer.class)
    @Builder.Default
    private LocalDateTime updateAt = LocalDateTime.now();

    @Builder.Default
    private int ttl = 86_400;

    @Builder.Default
    private int hitCount = 0;

    @Builder.Default
    private Map<String, Object> meta = new HashMap<>();

    public enum MemoryType {
        GLOBAL,
        PROJECT,
        SESSION,
        USER
    }

}
