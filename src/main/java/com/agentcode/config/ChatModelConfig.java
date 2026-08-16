package com.agentcode.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ChatModelConfig {

    @Bean
    public ChatClient chatClient(ChatModel chatModel) {
        // TODO: 按需配置 ChatClient
        return ChatClient.builder(chatModel).build();
    }
}
