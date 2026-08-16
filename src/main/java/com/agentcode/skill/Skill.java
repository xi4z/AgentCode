package com.agentcode.skill;

public record Skill(String name, String description, String systemPromptTemplate, java.util.List<String> allowedTools) {
}
