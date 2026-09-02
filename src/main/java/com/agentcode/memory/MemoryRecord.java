package com.agentcode.memory;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;


import java.io.IOException;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
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

    /**
     * 时间字段统一秒精度序列化。
     * 修复背景：LocalDateTime.now() 带纳秒，写入 ES 的 updateAt 形如 "2026-09-02T12:38:19.426542375"，
     * 而固定 pattern 的反序列化器解析不了纳秒精度，导致检索响应整体解码失败（召回全挂）。
     */
    @JsonSerialize(using = MemoryRecord.SecondPrecisionSerializer.class)
    @JsonDeserialize(using = MemoryRecord.FlexibleLocalDateTimeDeserializer.class)
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

    /** 序列化：截断到秒，保证 ES 中的 updateAt 精度固定 */
    public static class SecondPrecisionSerializer extends JsonSerializer<LocalDateTime> {
        @Override
        public void serialize(LocalDateTime value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
            if (value == null) {
                gen.writeNull();
                return;
            }
            gen.writeString(value.truncatedTo(ChronoUnit.SECONDS).toString());
        }
    }

    /** 反序列化：容忍 0~9 位小数秒（ISO 可变精度），兼容历史纳秒精度文档 */
    public static class FlexibleLocalDateTimeDeserializer extends JsonDeserializer<LocalDateTime> {
        @Override
        public LocalDateTime deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            String text = parser.getValueAsString();
            if (text == null || text.isBlank()) {
                return null;
            }
            String trimmed = text.trim();
            try {
                return LocalDateTime.parse(trimmed);
            } catch (Exception e) {
                // 兜底：截到秒级再解析
                int cut = Math.min(trimmed.length(), 19);
                return LocalDateTime.parse(trimmed.substring(0, cut));
            }
        }
    }

}
