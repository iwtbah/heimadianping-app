package com.zwz5.common.cache;

import com.zwz5.common.redis.RedisData;
import com.zwz5.common.utils.JsonUtils;
import com.zwz5.exception.NullException;
import com.zwz5.pojo.entity.Shop;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.zwz5.constants.RedisConstants.*;

/**
 * 基于 Redis 的缓存实现。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisCacheClient implements CacheClient {

    private final StringRedisTemplate stringRedisTemplate;
    private final JsonUtils jsonUtils;

    @Override
    public <T> void set(String key, T value) {
        set(key, value, null, null);
    }

    @Override
    public <T> void set(String key, T value, Long expire, TimeUnit timeUnit) {
        Objects.requireNonNull(key, "key must not be null");
        if (value == null) {
            stringRedisTemplate.opsForValue().set(key, "");
            return;
        }
        if (expire == null || timeUnit == null || expire <= 0) {
            stringRedisTemplate.opsForValue().set(key, convertToString(value));
        } else {
            stringRedisTemplate.opsForValue().set(key, convertToString(value), expire, timeUnit);
        }
    }

    @Override
    public <T> void setWithLogicalExpire(String key, T value, Long expire, TimeUnit timeUnit) {
        Objects.requireNonNull(key, "key must not be null");
        if (value == null) {
            stringRedisTemplate.opsForValue().set(key, "");
            return;
        }
        if (expire == null || timeUnit == null || expire <= 0) {
            stringRedisTemplate.opsForValue().set(key, convertToString(value));
        } else {
            RedisData redisData = RedisData.builder()
                    .data(convertToString(value))
                    .expireTime(LocalDateTime.now().plusSeconds(timeUnit.toSeconds(expire)))
                    .build();
            stringRedisTemplate.opsForValue().set(key, convertToString(redisData));
        }
    }


    @Override
    public String get(String key) {
        Objects.requireNonNull(key, "key must not be null");
        try {
            return stringRedisTemplate.opsForValue().get(key);
        } catch (DataAccessException ex) {
            log.warn("Read cache failed. key={}", key, ex);
            return null;
        }
    }

    @Override
    public <T> T get(String key, Class<T> type) {
        Objects.requireNonNull(type, "type must not be null");
        String value = get(key);
        if (value == null) {
            return null;
        }
        if (type == String.class) {
            return type.cast(value);
        }
        return jsonUtils.jsonToBean(value, type);
    }

    /**
     * 缓存穿透方法
     *
     * @return
     */
    public <T, R> R queryByPassThrough(String prefix, T id, Class<R> type, Function<T, R> dbFallback, Long expire, TimeUnit timeUnit) {
        Objects.requireNonNull(id, "key must not be null");
        String key = prefix + id;
        String jsonStr = stringRedisTemplate.opsForValue().get(key);
        // 命中cache，反序列化为对象返回
        if (StringUtils.hasText(jsonStr)) {
            return jsonUtils.jsonToBean(jsonStr, type);
        }
        // 命中empty cache
        if (jsonStr != null && jsonStr.isEmpty()) {
            return null;
        }
        // 未命中查询db
        R r = (R) dbFallback.apply(id);
        if (r == null) {
            // 构建empty cache 为短TTL
            this.set(String.valueOf(key), "", expire / 10, timeUnit);
            return null;
        }
        this.set(String.valueOf(key), r, expire, timeUnit);
        return r;
    }

    private <T> String convertToString(T value) {
        if (value instanceof String str) {
            return str;
        }
        return jsonUtils.beanToJson(value);
    }
}
