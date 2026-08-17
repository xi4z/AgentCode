package com.agentcode.config;

import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SaverConfig {

    @Bean
    public MemorySaver memorySaver(){
        return new MemorySaver();
    }
}
