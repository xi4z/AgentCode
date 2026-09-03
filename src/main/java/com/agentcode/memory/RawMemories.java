package com.agentcode.memory;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class RawMemories {

    List<RawMemory> memories;

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown=true)
    public static class RawMemory {
        String action;
        String existingMemoryId;
        String type;
        String scope;
        String content;
        String dedupeKey;
        Double confidence;
        Double importance;
        Integer ttlSeconds;
        List<String> tags;
        String reason;

    }

}
