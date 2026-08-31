package com.agentcode.memory;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@AllArgsConstructor
public class MemoryRecord {
    String memoryId;
    MemoryType type;
    String content;
    double confidence;
    LocalDateTime updateAt;
    int ttl;
    Map<String, Object> meta;


    public enum MemoryType {
        GLOBAL,
        PROJECT,
        SESSION,
        USER
    }

}
