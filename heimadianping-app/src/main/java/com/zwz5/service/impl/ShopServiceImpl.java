package com.zwz5.service.impl;

import com.zwz5.common.cache.CacheClient;
import com.zwz5.common.cache.RedisCacheClient;
import com.zwz5.exception.LockException;
import com.zwz5.exception.NullException;
import com.zwz5.pojo.entity.Shop;
import com.zwz5.mapper.ShopMapper;
import com.zwz5.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zwz5.common.utils.JsonUtils;
import com.zwz5.common.redis.RedisData;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static com.zwz5.constants.RedisConstants.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private JsonUtils jsonUtils;
    @Resource
    private RedisCacheClient redisCacheClient;


    // 避免使用公共 ForkJoinPool，异步任务有自己可观测、可限流的线程池
    @Resource
    @Qualifier("cacheOpsExecutor")
    private Executor cacheOpsExecutor;

    @Override
    public Shop queryById(Long id) {
        // 缓存穿透解决方案
        return queryByPassThrough(id);

        // 缓存击穿 互斥锁方案
        //return queryWithMutex(id);

        // 缓存击穿 逻辑过期 + 异步缓存重建方案
        //return queryWithLogicalExpire(id);

    }

    @Override
    @Transactional
    public void update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            throw new NullException("商品id不能为空");
        }
        // 更新数据库
        updateById(shop);
        // 删除对应缓存
        String key = CACHE_SHOP_KEY + shop.getId();
        stringRedisTemplate.delete(key);
        // 延迟双删，避免并发环境旧值回填
        // 在线程池中异步重建缓存
        CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            stringRedisTemplate.delete(key);
        }, cacheOpsExecutor);
    }

    /**
     * 缓存穿透解决方法
     *
     * @param id
     * @return
     */
    public Shop queryByPassThrough(Long id) {

        // TODO Redisson 的 RBloomFilter 来实现布隆过滤器，后续补充
        // TTL抖动
        long jitterMinutes = ThreadLocalRandom.current().nextLong(1, 3);
        return redisCacheClient.queryByPassThrough(
                CACHE_SHOP_KEY,
                id,
                Shop.class,
                this::getById,
                CACHE_SHOP_TTL + jitterMinutes,
                TimeUnit.MINUTES);
    }

    /**
     * 缓存击穿 互斥锁方案
     *
     * @param id
     * @return
     */
    public Shop queryWithMutex(Long id) {

        // TODO Redisson 的 RBloomFilter 来实现布隆过滤器，后续补充

        // 查询缓存
        final String key = CACHE_SHOP_KEY + id;
        final String lockKey = LOCK_SHOP_KEY + id;

        String cachedShopJson = stringRedisTemplate.opsForValue().get(key);

        // 命中有效缓存，反序列化为对象返回
        if (StringUtils.hasText(cachedShopJson)) {
            return jsonUtils.jsonToBean(cachedShopJson, Shop.class);
        }
        // 命中空值缓存，则Result.fail
        if (cachedShopJson != null && cachedShopJson.isEmpty()) {
            throw new NullException("未查询到值");
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
                cachedShopJson = stringRedisTemplate.opsForValue().get(key);
                if (StringUtils.hasText(cachedShopJson)) {
                    long waitedMs = (System.nanoTime() - lockWaitStart) / 1_000_000;
                    log.info("shopId={} 获取锁前缓存已被其他线程填充，等待{}毫秒，尝试{}次\n", id, waitedMs, attempts);
                    return jsonUtils.jsonToBean(cachedShopJson, Shop.class);
                }
                if (cachedShopJson != null && cachedShopJson.isEmpty()) {
                    throw new NullException("未查询到值");
                }
            }
            long lockCostMs = (System.nanoTime() - lockWaitStart) / 1_000_000;
            log.info("shopId={} 成功获取锁，用时{}毫秒，尝试{}次\n", id, lockCostMs, attempts);
            // 获取锁成功，double check 缓存是否命中，命中则直接返回
            cachedShopJson = stringRedisTemplate.opsForValue().get(key);
            if (StringUtils.hasText(cachedShopJson)) {
                return jsonUtils.jsonToBean(cachedShopJson, Shop.class);
            }
            if (cachedShopJson != null && cachedShopJson.isEmpty()) {
                throw new NullException("未查询到值");
            }

            final long rebuildStart = System.nanoTime(); // 开始记录重建缓存时间
            // 未命中则查询数据库
            Shop shop = getById(id);
            // 数据库没有数据：解决缓存穿透，将空值缓存（短 TTL）
            if (shop == null) {
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                long rebuildMs = (System.nanoTime() - rebuildStart) / 1_000_000;
                log.info("shopId={} 缓存重建结果为空, 用时{}毫秒\n", id, rebuildMs);
                throw new NullException("未查询到值");
            }
            // 写入有效缓存（业务 TTL + 随机抖动）
            long jitterMinutes = ThreadLocalRandom.current().nextLong(1, 3); // 1~5 分钟扰动
            stringRedisTemplate.opsForValue().set(
                    key,
                    jsonUtils.beanToJson(shop),
                    CACHE_SHOP_TTL + jitterMinutes,
                    TimeUnit.MINUTES
            );
            long rebuildMs = (System.nanoTime() - rebuildStart) / 1_000_000;
            log.info("shopId={} 缓存重建完成\n, cost={}ms ttl={}min(+{}jitter)", id, rebuildMs, CACHE_SHOP_TTL, jitterMinutes);
            return shop;
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
     * 1. 读取 Redis 逻辑过期结构，命中空串说明数据库无记录，直接抛出 NullException。
     * 2. 命中后反序列化 RedisData，未过期直接返回 Shop。
     * 3. 已过期则尝试获取 lock:shop:id 互斥锁并做二次校验；获取成功后异步查库写回新的逻辑过期数据。
     * 4. 未获得锁的线程与加锁线程的同步返回值均为旧数据，以保证接口可用性。
     */
    public Shop queryWithLogicalExpire(Long id) {
        final String key = CACHE_SHOP_KEY + id;
        String cachedShopJson = stringRedisTemplate.opsForValue().get(key);
        long jitterMinutes = ThreadLocalRandom.current().nextLong(1, 3);
        // 命中空串表示数据库无记录
        if (cachedShopJson != null && cachedShopJson.isEmpty()) {
            throw new NullException("未查询到值");
        }
        // 缓存命中后解析逻辑过期结构
        if (StringUtils.hasText(cachedShopJson)) {
            RedisData redisData = jsonUtils.jsonToBean(cachedShopJson, RedisData.class);
            Shop shop = jsonUtils.convertValue(redisData.getData(), Shop.class);
            // 缓存仍有效直接返回
            LocalDateTime expireTime = redisData.getExpireTime();
            if (expireTime != null && expireTime.isAfter(LocalDateTime.now())) {
                return shop;
            }
            // 缓存已过期则尝试获取互斥锁
            final String lockKey = LOCK_SHOP_KEY + id;
            String token = null;
            // 拿到锁后在线程池中异步重建
            if ((token = tryLock(lockKey, LOCK_SHOP_TTL)) != null) {
                try {
                    // double check 避免重复重建
                    String cachedShopJson_dc = stringRedisTemplate.opsForValue().get(key);
                    // 再次解析逻辑过期结构
                    if (StringUtils.hasText(cachedShopJson_dc)) {
                        redisData = jsonUtils.jsonToBean(cachedShopJson_dc, RedisData.class);
                        shop = jsonUtils.convertValue(redisData.getData(), Shop.class);
                        // 二次校验仍有效直接返回
                        expireTime = redisData.getExpireTime();
                        if (expireTime != null && expireTime.isAfter(LocalDateTime.now())) {
                            return shop;
                        }
                    }
                    // 在线程池中异步重建缓存
                    CompletableFuture.runAsync(() -> {
                        // 从数据库查询最新 Shop
                        Shop latest = getById(id);
                        if (latest == null) {
                            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL + jitterMinutes, TimeUnit.MINUTES);
                        } else {
                            // 写入新的逻辑过期数据
                            stringRedisTemplate.opsForValue().set(
                                    key,
                                    jsonUtils.beanToJson(RedisData.builder().data(latest).expireTime(LocalDateTime.now().plusMinutes(CACHE_SHOP_TTL + jitterMinutes)).build())
                            );
                        }
                    }, cacheOpsExecutor);
                } catch (Exception e) {
                    // 记录日志，避免向外抛异常
                    log.error("async rebuild cache failed, key={}", key);
                } finally {
                    // 释放锁
                    // 仅在持有锁的情况下按 token 释放，避免误删他人锁
                    if (token != null) {
                        unlock(lockKey, token);
                    }
                }
            }
            return shop;
        }

        // 缓存未命中则回源数据库并写入逻辑过期结构
        // 从数据库查询最新 Shop
        Shop shop = getById(id);
        if (shop == null) {
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL + jitterMinutes, TimeUnit.MINUTES);
            throw new NullException("未查询到值");
        }
        // 写入新的逻辑过期数据
        stringRedisTemplate.opsForValue().set(
                key,
                jsonUtils.beanToJson(RedisData.builder().data(shop).expireTime(LocalDateTime.now().plusMinutes(CACHE_SHOP_TTL + jitterMinutes)).build())
        );
        return shop;
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
