package com.zwz5.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean("cacheOpsExecutor")
    public Executor cacheOpsExecutor() {
        ThreadPoolTaskExecutor tp = new ThreadPoolTaskExecutor();
        tp.setCorePoolSize(2);
        tp.setMaxPoolSize(8);
        tp.setQueueCapacity(200);
        tp.setThreadNamePrefix("cache-ops-");
        tp.setKeepAliveSeconds(60);
        tp.setAllowCoreThreadTimeOut(true);
        tp.initialize();
        return tp;
    }
}