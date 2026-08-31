package com.agentcode.memory;

public record ScoredMemory(
        MemoryRecord memory,
        Double score,
        Double cosineScore
) {

    public ScoredMemory(MemoryRecord memory, Double score) {
        this(memory, score, 0.0d);
    }

    public double esScore() {
        return score == null ? 0.0d : score;
    }

    public double cosine() {
        return cosineScore == null ? 0.0d : cosineScore;
    }
}