package com.zwz5.common.cache;

import java.util.concurrent.TimeUnit;

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
}
