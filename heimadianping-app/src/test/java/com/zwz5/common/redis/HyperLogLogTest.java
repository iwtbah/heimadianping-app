package com.zwz5.common.redis;

import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

@SpringBootTest
public class HyperLogLogTest {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Test
    void testUVInsert() {
        // 准备模拟数组
        String[] shops = new String[1000];
        // uv:shop:20260503 1001
        int index = 0;
        for (int i = 0; i < 1000000;i++) {
            shops[index] = "id:" + i;
            index++;
            if (index % 1000 == 0) {
                index = 0;
                stringRedisTemplate.opsForHyperLogLog().add("uv:shop:20260503", shops);
            }
        }
    }

    @Test
    void testUVCount() {
        Long count = stringRedisTemplate.opsForHyperLogLog().size("uv:shop:20260503");
        System.out.println(count);
    }
}
