package com.agentcode.config;

import com.alibaba.cloud.ai.graph.checkpoint.savers.redis.RedisSaver;
import com.agentcode.properties.AgentCodeProperties;
import lombok.RequiredArgsConstructor;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Checkpoint 存储装配（Redis / Redisson）。
 * 配置统一走 {@link AgentCodeProperties} 的 agentcode.checkpoint.redis 绑定。
 */
@Configuration
@RequiredArgsConstructor
public class SaverConfig {

    private final AgentCodeProperties properties;

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient() {
        AgentCodeProperties.Checkpoint.Redis redis = properties.getCheckpoint().getRedis();
        Config config = new Config();
        var single = config.useSingleServer()
                .setAddress(redis.getAddress())
                .setDatabase(redis.getDatabase())
                // 连接与超时治理：避免 Redis 抖动时 checkpoint 读写长时间挂起
                .setConnectTimeout(5000)    // 建连超时 5s
                .setTimeout(10000)          // 命令超时 10s
                .setRetryAttempts(3)        // 失败重试 3 次
                .setRetryInterval(2000);    // 重试间隔 2s
        String password = redis.getPassword();
        if (password != null && !password.isBlank()) {
            single.setPassword(password);
        }
        return Redisson.create(config);
    }

    @Bean
    public RedisSaver redisSaver(RedissonClient redissonClient) {
        return RedisSaver.builder().redisson(redissonClient).build();
    }
}
