package com.zwz5.common.cache;

import com.zwz5.common.redis.RedisData;
import com.zwz5.common.utils.JsonUtils;
import com.zwz5.exception.LockException;
import com.zwz5.exception.NullException;
import com.zwz5.pojo.entity.Shop;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.baomidou.mybatisplus.extension.toolkit.Db.getById;
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
        // TTL抖动
        long jitterMinutes = ThreadLocalRandom.current().nextLong(1, 3);
        expire += jitterMinutes;
        if (r == null) {
            // 构建empty cache 为短TTL
            this.set(key, "", expire / 10, timeUnit);
            return null;
        }
        this.set(key, r, expire, timeUnit);
        return r;
    }

    /**
     * 缓存击穿 互斥锁方案
     *
     * @param id
     * @return
     */
    public <T, R> R queryWithMutex(String prefix, T id, Class<R> type, Function<T, R> dbFallback, Long expire, TimeUnit timeUnit) {
        Objects.requireNonNull(id, "key must not be null");
        String key = prefix + id;
        String lockKey = LOCK_SHOP_KEY + id;

        String jsonStr = stringRedisTemplate.opsForValue().get(key);
        // 命中有效缓存，反序列化为对象返回
        if (StringUtils.hasText(jsonStr)) {
            return jsonUtils.jsonToBean(jsonStr, type);
        }
        // 命中empty cache
        if (jsonStr != null && jsonStr.isEmpty()) {
            return null;
        }
        // 未命中 互斥锁重建缓存
        // 有限次自旋 + 指数退避（避免热点轮询压垮 Redis）
        String token = null;
        long backoff = 50L;                 // 起始退避 50ms
        final long maxBackoff = 500L;       // 单次最大退避 500ms
        int attempts = 0;
        final int maxAttempts = 8;          // 最多自旋 8 次（~1.3s 左右）

        final long lockWaitStart = System.nanoTime(); // A) 开始统计拿锁耗时

        try {
            while ((token = tryLock(lockKey, LOCK_SHOP_TTL)) == null) {
                // 退避等待并加入抖动，降低同步化
                long jitter = ThreadLocalRandom.current().nextLong(0, backoff / 3 + 1);
                Thread.sleep(backoff + jitter);
                if (++attempts >= maxAttempts) {
                    long waitMs = (System.nanoTime() - lockWaitStart) / 1_000_000;
                    log.warn("shopId={} 获取锁超时，等待{}毫秒，尝试{}次\n", id, waitMs, attempts);
                    throw new LockException("锁占用，请稍后重试");
                }
                backoff = Math.min(backoff * 2, maxBackoff);
                // 每轮重查一次缓存，避免无意义等待
                jsonStr = stringRedisTemplate.opsForValue().get(key);
                if (StringUtils.hasText(jsonStr)) {
                    long waitedMs = (System.nanoTime() - lockWaitStart) / 1_000_000;
                    log.info("shopId={} 获取锁前缓存已被其他线程填充，等待{}毫秒，尝试{}次\n", id, waitedMs, attempts);
                    return jsonUtils.jsonToBean(jsonStr, type);
                }
                if (jsonStr != null && jsonStr.isEmpty()) {
                    return null;
                }
            }
            long lockCostMs = (System.nanoTime() - lockWaitStart) / 1_000_000;
            log.info("shopId={} 成功获取锁，用时{}毫秒，尝试{}次\n", id, lockCostMs, attempts);
            // 获取锁成功，double check 缓存是否命中，命中则直接返回
            jsonStr = stringRedisTemplate.opsForValue().get(key);
            if (StringUtils.hasText(jsonStr)) {
                return jsonUtils.jsonToBean(jsonStr, type);
            }
            if (jsonStr != null && jsonStr.isEmpty()) {
                return null;
            }
            final long rebuildStart = System.nanoTime(); // 开始记录重建缓存时间
            // 未命中则查询数据库

            R r = (R) dbFallback.apply(id);
            // 数据库没有数据：解决缓存穿透，将空值缓存（短 TTL）
            if (r == null) {
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, timeUnit);
                long rebuildMs = (System.nanoTime() - rebuildStart) / 1_000_000;
                log.info("shopId={} 缓存重建结果为空, 用时{}毫秒\n", id, rebuildMs);
                return null;
            }
            // 写入有效缓存（业务 TTL + 随机抖动）
            long jitterMinutes = ThreadLocalRandom.current().nextLong(1, 3); // 1~5 分钟扰动
            stringRedisTemplate.opsForValue().set(
                    key,
                    jsonUtils.beanToJson(r),
                    expire + jitterMinutes,
                    timeUnit
            );
            long rebuildMs = (System.nanoTime() - rebuildStart) / 1_000_000;
            log.info("shopId={} 缓存重建完成\n, cost={}ms ttl={}min(+{}jitter)", id, rebuildMs, expire, jitterMinutes);
            return r;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        } finally {
            // 释放锁
            // 仅在持有锁的情况下按 token 释放，避免误删他人锁
            if (token != null) {
                boolean unlocked = unlock(lockKey, token);
                log.debug("shopId={} 释放锁结果={}\n", id, unlocked);
            }
        }
    }



    /**
     * 缓存击穿处理：逻辑过期 + 异步重建
     * 使用场景：热点店铺读多写少，允许短暂返回旧值。
     * 1. 读取 Redis 逻辑过期结构，命中空串说明数据库无记录，直接返回null。
     * 2. 命中后反序列化 RedisData，未过期直接返回 Shop。
     * 3. 已过期则尝试获取 lock:shop:id 互斥锁并做二次校验；获取成功后异步查库写回新的逻辑过期数据。
     * 4. 未获得锁的线程与加锁线程的同步返回值均为旧数据，以保证接口可用性。
     */
    public <T, R> R queryWithLogicalExpire(String prefix, T id, Class<R> type, Function<T, R> dbFallback, Long expire, TimeUnit timeUnit) {
        Objects.requireNonNull(id, "key must not be null");
        String key = prefix + id;
        long jitterMinutes = ThreadLocalRandom.current().nextLong(1, 3);
        String jsonStr = stringRedisTemplate.opsForValue().get(key);
        // 命中empty cache
        if (jsonStr != null && jsonStr.isEmpty()) {
            return null;
        }
        // 命中cache,解析逻辑过期结构
        if (StringUtils.hasText(jsonStr)) {
            RedisData redisData = jsonUtils.jsonToBean(jsonStr, RedisData.class);
            R r = jsonUtils.convertValue(redisData.getData(), type);
            // 缓存仍有效直接返回
            LocalDateTime expireTime = redisData.getExpireTime();
            if (expireTime != null && expireTime.isAfter(LocalDateTime.now())) {
                return r;
            }
            // 缓存已过期则尝试获取互斥锁
            final String lockKey = LOCK_SHOP_KEY + id;
            String token = null;
            // 拿到锁后在线程池中异步重建
            if ((token = tryLock(lockKey, LOCK_SHOP_TTL)) != null) {
                try {
                    // double check
                    String jsonStr_dc = stringRedisTemplate.opsForValue().get(key);
                    if (StringUtils.hasText(jsonStr_dc)) {
                        redisData = jsonUtils.jsonToBean(jsonStr_dc, RedisData.class);
                        r = jsonUtils.convertValue(redisData.getData(), type);
                        expireTime = redisData.getExpireTime();
                        if (expireTime != null && expireTime.isAfter(LocalDateTime.now())) {
                            return r;
                        }
                    }
                    // 异步重建缓存
                    CompletableFuture.runAsync(() -> {
                        // 从db查询最新
                        R latest = (R) dbFallback.apply(id);
                        if (latest == null) {
                            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL + jitterMinutes, timeUnit);
                        } else {
                            // 写入新的逻辑过期数据
                            stringRedisTemplate.opsForValue().set(
                                    key,
                                    jsonUtils.beanToJson(RedisData.builder().data(latest).expireTime(LocalDateTime.now().plusMinutes(expire + jitterMinutes)).build())
                            );
                        }
                    }, cacheOpsExecutor);
                } catch (Exception e) {
                    // 记录日志
                    log.error("async rebuild cache failed, key={}", key);
                } finally {
                    // 释放锁
                    // 仅在持有锁的情况下按 token 释放，避免误删他人锁
                    unlock(lockKey, token);

                }
            }
            return r;
        }

        // 缓存未命中则回源数据库并写入逻辑过期结构
        // 从数据库查询最新 Shop
        R r =  (R) dbFallback.apply(id);
        if (r == null) {
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL + jitterMinutes, timeUnit);
            return null;
        }
        // 写入新的逻辑过期数据
        stringRedisTemplate.opsForValue().set(
                key,
                jsonUtils.beanToJson(RedisData.builder().data(r).expireTime(LocalDateTime.now().plusMinutes(expire + jitterMinutes)).build())
        );
        return r;
    }


    private <T> String convertToString(T value) {
        if (value instanceof String str) {
            return str;
        }
        return jsonUtils.beanToJson(value);
    }

    /**
     * 尝试获取分布式锁（原子设置过期），返回锁token；失败返回 null
     *
     * @param key           锁的键
     * @param expireSeconds 过期时间（秒）
     * @return 是否成功获取锁
     */
    public String tryLock(String key, long expireSeconds) {
        String token = UUID.randomUUID().toString();
        Boolean ok = stringRedisTemplate.opsForValue().setIfAbsent(key, token, expireSeconds, TimeUnit.SECONDS);
        return (ok != null && ok) ? token : null;
    }

    private static final String UNLOCK_LUA =
            "if redis.call('get', KEYS[1]) == ARGV[1] then " +
                    "  return redis.call('del', KEYS[1]) " +
                    "else return 0 end";

    /**
     * 释放分布式锁（仅当 token 匹配时删除）
     *
     * @param key   锁的键
     * @param token 锁token
     * @return 是否成功释放锁
     */
    public boolean unlock(String key, String token) {
        Long res = stringRedisTemplate.execute(
                new DefaultRedisScript<>(UNLOCK_LUA, Long.class),
                Collections.singletonList(key),
                token
        );
        return res != null && res > 0;
    }
}
