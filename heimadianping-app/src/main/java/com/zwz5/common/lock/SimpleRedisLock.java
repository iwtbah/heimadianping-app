package com.zwz5.common.lock;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
public class SimpleRedisLock implements Ilock {

    private final StringRedisTemplate stringRedisTemplate;
    private final String name;

    public SimpleRedisLock(StringRedisTemplate stringRedisTemplate, String name) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.name = name;
    }

    private final String KEY_PREFIX = "lock:";
    private static final String ID_PREFIX = UUID.randomUUID() + "-";

    private final DefaultRedisScript<Long> UNLOCK_SCRIPT;

    {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("lock/unlock_script.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    @Override
    public boolean tryLock(long timeout) {
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(KEY_PREFIX + name, threadId, timeout, TimeUnit.SECONDS);
        // 自动拆箱防止空异常 success != null && success：
        return Boolean.TRUE.equals(success);
    }

    /**
     * 使用lua脚本释放锁，保证原子性操作
     */
    public void unlock() {
        String lock_token = ID_PREFIX + Thread.currentThread().getId();
        stringRedisTemplate.execute(
                UNLOCK_SCRIPT,
                Collections.singletonList( KEY_PREFIX + name),
                lock_token);
    }
    /*
    @Override
    public void unlock() {
        // 获取线程标示
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        // 获取锁中的标示
        String id = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
        // 判断标示是否一致
        if(threadId.equals(id)) {
            // 释放锁
            stringRedisTemplate.delete(KEY_PREFIX + name);
        }
    }
    */

}