package com.zwz5.common.cache;

import com.zwz5.common.redis.RedisData;
import com.zwz5.common.utils.JsonUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.zwz5.constants.RedisConstants.CACHE_NULL_TTL;


@Slf4j
@Component
@RequiredArgsConstructor
public class RedissonCacheClient implements CacheClient {
    private final StringRedisTemplate stringRedisTemplate;
    private final JsonUtils jsonUtils;

    // 分布式锁
    private final RedissonClient redissonClient;

    // 避免使用公共 ForkJoinPool，异步任务有自己可观测、可限流的线程池
    private final Executor cacheOpsExecutor;

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
                    .data(value) // 这里直接用对象
                    .expireTime(LocalDateTime.now().plusSeconds(timeUnit.toSeconds(expire)))
                    .build();
            stringRedisTemplate.opsForValue().set(key, jsonUtils.beanToJson(redisData));
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

    @Override
    /**
     * 热点数据逻辑过期 + 异步重建（Redisson 锁版）
     * 适用场景：读多写少的热点 key，允许短暂返回旧值以提升可用性。
     * 流程：
     * 1) 先查缓存：空串视为数据库无值直接返回；命中且未过期直接返回。
     * 2) 命中过期：先返回旧值保障 RT，同时尝试加锁异步重建（锁内 double check 避免重复回源）。
     * 3) 缓存未命中：尝试加锁串行重建；没拿到锁则短暂等待他人构建后再读，避免击穿成风暴。
     * 4) TTL 加入抖动，空值与正常值统一 timeUnit，降低同刻失效与穿透风险。
     */
    public <T, R> R queryWithLogicalExpire(String prefix, T id, Class<R> type, Function<T, R> dbFallback, Long expire, TimeUnit timeUnit) {
        Objects.requireNonNull(id, "key must not be null");
        Objects.requireNonNull(expire, "expire must not be null");
        Objects.requireNonNull(timeUnit, "timeUnit must not be null");
        Objects.requireNonNull(dbFallback, "dbFallback must not be null");
        Objects.requireNonNull(type, "type must not be null");

        String key = prefix + id;
        // 抖动保持与入参 timeUnit 一致，避免混用秒/分
        long jitter = ThreadLocalRandom.current().nextLong(1, 3);
        long dataTtl = expire + jitter;
        long nullTtl = CACHE_NULL_TTL + jitter;
        // 统一锁前缀
        String lockKey = buildLockKey(key);

        // 命中空值缓存，预防缓存穿透
        String jsonStr = stringRedisTemplate.opsForValue().get(key);
        if (jsonStr != null && jsonStr.isEmpty()) {
            return null;
        }

        // 命中缓存
        if (StringUtils.hasText(jsonStr)) {
            LogicalValue<R> logicalValue = parseLogicalValue(jsonStr, type);
            if (logicalValue == null) {
                return null;
            }
            if (logicalValue.expireTime() != null && logicalValue.expireTime().isAfter(LocalDateTime.now())) {
                return logicalValue.value();
            }
            // 已过期：异步重建，但先返回旧值保证可用性
            triggerAsyncRebuild(key, lockKey, id, dbFallback, dataTtl, nullTtl, timeUnit, type);
            return logicalValue.value();
        }

        // 未命中：串行重建（竞争失败则短暂等待他人构建）
        return rebuildOnMiss(key, lockKey, id, dbFallback, dataTtl, nullTtl, timeUnit, type);
    }


    private <T> String convertToString(T value) {
        if (value instanceof String str) {
            return str;
        }
        return jsonUtils.beanToJson(value);
    }

    // 统一锁前缀，防止业务 key 变更导致锁失效
    private String buildLockKey(String key) {
        return "lock:" + key;
    }

    // 解析逻辑过期结构，返回值与过期时间包装
    private <R> LogicalValue<R> parseLogicalValue(String jsonStr, Class<R> type) {
        if (!StringUtils.hasText(jsonStr)) {
            return null;
        }
        RedisData redisData = jsonUtils.jsonToBean(jsonStr, RedisData.class);
        R value = jsonUtils.convertValue(redisData.getData(), type);
        return new LogicalValue<>(value, redisData.getExpireTime());
    }

    // 写入逻辑过期结构，使用统一 TTL 单位
    private <R> void writeLogicalValue(String key, R data, long ttl, TimeUnit unit) {
        RedisData redisData = RedisData.builder()
                .data(data)
                .expireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(ttl)))
                .build();
        stringRedisTemplate.opsForValue().set(key, jsonUtils.beanToJson(redisData));
    }

    // 异步重建：仅在拿到锁后回源并 double check，避免重复重建
    private <T, R> void triggerAsyncRebuild(String key,
                                            String lockKey,
                                            T id,
                                            Function<T, R> dbFallback,
                                            long dataTtl,
                                            long nullTtl,
                                            TimeUnit timeUnit,
                                            Class<R> type) {
        CompletableFuture.runAsync(() -> {
            RLock lock = redissonClient.getLock(lockKey);
            boolean locked = false;
            try {
                locked = lock.tryLock();
                if (!locked) {
                    return;
                }
                // double check：锁内再校验是否已被其他线程重建
                String latestStr = stringRedisTemplate.opsForValue().get(key);
                LogicalValue<R> latest = parseLogicalValue(latestStr, type);
                if (latest != null && latest.expireTime() != null && latest.expireTime().isAfter(LocalDateTime.now())) {
                    return;
                }

                R latestData = dbFallback.apply(id);
                if (latestData == null) {
                    stringRedisTemplate.opsForValue().set(key, "", nullTtl, timeUnit);
                    return;
                }
                writeLogicalValue(key, latestData, dataTtl, timeUnit);
            } catch (Exception e) {
                log.error("async rebuild cache failed, key={}", key, e);
            } finally {
                if (locked && lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }
        }, cacheOpsExecutor);
    }

    // 缓存未命中时的串行重建逻辑，没拿到锁会短暂等待他人重建
    private <T, R> R rebuildOnMiss(String key,
                                   String lockKey,
                                   T id,
                                   Function<T, R> dbFallback,
                                   long dataTtl,
                                   long nullTtl,
                                   TimeUnit timeUnit,
                                   Class<R> type) {
        RLock lock = redissonClient.getLock(lockKey);
        boolean locked = false;
        try {
            locked = lock.tryLock();
            if (!locked) {
                // 等待其他线程重建，短暂重试两次
                for (int i = 0; i < 2; i++) {
                    Thread.sleep(50);
                    String cached = stringRedisTemplate.opsForValue().get(key);
                    if (cached != null && cached.isEmpty()) {
                        return null;
                    }
                    if (StringUtils.hasText(cached)) {
                        LogicalValue<R> logicalValue = parseLogicalValue(cached, type);
                        return logicalValue == null ? null : logicalValue.value();
                    }
                }
                return null;
            }
            R latest = dbFallback.apply(id);
            if (latest == null) {
                stringRedisTemplate.opsForValue().set(key, "", nullTtl, timeUnit);
                return null;
            }
            writeLogicalValue(key, latest, dataTtl, timeUnit);
            return latest;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private record LogicalValue<R>(R value, LocalDateTime expireTime) {
    }
}
