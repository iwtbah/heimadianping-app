package com.zwz5.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedissonConfig {

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient() {
        Config config = new Config();

        // 单机模式
        config.useSingleServer()
                .setAddress("redis://127.0.0.1:6379")
                .setPassword(null)
                .setDatabase(0);

        return Redisson.create(config);
    }
}
