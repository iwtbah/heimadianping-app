package com.zwz5.common.cache;

import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * 抽象缓存客户端，约定最基本的读写能力。
 */
public interface CacheClient {

    /**
     * 写入缓存，使用默认过期策略（无过期或由实现决定）。
     */
    <T> void set(String key, T value);

    /**
     * 写入缓存并指定存活时间。
     */
    <T> void set(String key, T value, Long expire, TimeUnit timeUnit);

    /**
     * 写入缓存并指定逻辑过期时间
     */
    <T> void setWithLogicalExpire(String key, T value, Long expire, TimeUnit timeUnit);

    /**
     * 按字符串形式读取缓存。
     */
    String get(String key);

    /**
     * 从缓存中读取并转换成目标类型。
     */
    <T> T get(String key, Class<T> type);

    /**
     * 逻辑过期 + 异步缓存重建
     * @param prefix 业务前缀
     * @param id key
     * @param type 缓存类型
     * @param dbFallback 数据库操作
     * @param expire 过期时间
     * @param timeUnit 时间单位
     * @return
     * @param <T> key
     * @param <R> 返回类型
     */
    <T, R> R queryWithLogicalExpire(String prefix, T id, Class<R> type, Function<T, R> dbFallback, Long expire, TimeUnit timeUnit);
}
