package com.agentcode.memory;

import java.time.LocalDateTime;
import java.util.Map;

public record MemoryRecord(
        MemoryType type,
        String content,
        double confidence,
        LocalDateTime updateAt,
        int TTL,
        Map<String, Object> meta
) {
    public enum MemoryType {
        GLOBAL,
        PROJECT,
        SESSION,
        USER
    }

}
