package com.agentcode.config;

import com.alibaba.cloud.ai.graph.checkpoint.savers.redis.RedisSaver;
import lombok.RequiredArgsConstructor;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@RequiredArgsConstructor
public class SaverConfig {

    @Value("${agentcode.checkpoint.redis.address:redis://127.0.0.1:6379}")
    private String redisAddress;

    @Value("${agentcode.checkpoint.redis.password:}")
    private String redisPassword;

    @Value("${agentcode.checkpoint.redis.database:0}")
    private int redisDatabase;

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient() {
        Config config = new Config();
        var single = config.useSingleServer()
                .setAddress(redisAddress)
                .setDatabase(redisDatabase);
        if (redisPassword != null && !redisPassword.isBlank()) {
            single.setPassword(redisPassword);
        }
        return Redisson.create(config);
    }

    @Bean
    public RedisSaver redisSaver(RedissonClient redissonClient) {
        return RedisSaver.builder().redisson(redissonClient).build();
    }
}