package com.agentcode.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** WebSocket 协议使用 Jackson 2 序列化；Spring Boot 4 默认的 Jackson 3 不参与这里。 */
@Configuration(proxyBeanMethods = false)
public class JacksonConfig {

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }
}
