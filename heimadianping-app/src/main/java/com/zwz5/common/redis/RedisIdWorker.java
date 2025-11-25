package com.zwz5.common.redis;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Slf4j
@Component
@RequiredArgsConstructor
public class RedisIdWorker {
    // 生成时间戳
    // 2025-01-01 00:00:00 UTC 对应的 epoch 秒
    private static final long BEGIN_TIMESTAMP = 1735689600L;
    private static final int bits = 32;

    private final StringRedisTemplate stringRedisTemplate;

    public long nextId(String prefix) {
        // 1.生成时间戳
        LocalDateTime now = LocalDateTime.now();
        long now_second = now.toEpochSecond(ZoneOffset.UTC);
        long timestamp = now_second - BEGIN_TIMESTAMP;
        // 2.生成序列号
        // 序列号32位，用Redis自增实现 + 业务 日期前缀实现
        String date = now.format(DateTimeFormatter.BASIC_ISO_DATE);

        Long count = stringRedisTemplate.opsForValue().increment("icr:" + prefix + ":" + date);
        return timestamp << bits | count;
    }

}
