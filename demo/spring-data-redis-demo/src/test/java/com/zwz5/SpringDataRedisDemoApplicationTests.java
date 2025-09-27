package com.zwz5;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zwz5.pojo.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * @startuml
 * actor User
 * participant "RedisTemplate" as RT
 * participant "JdkSerializationRedisSerializer" as Serializer
 * participant "ByteArrayOutputStream" as BAOS
 *
 * User -> RT : opsForValue().set("name","zhangsan")
 * RT -> Serializer : serialize("zhangsan")
 * Serializer -> BAOS : write bytes
 * BAOS --> Serializer : byte[]
 * Serializer --> RT : byte[]
 * RT --> User : OK
 * @enduml
 */

@SpringBootTest
class SpringDataRedisDemoApplicationTests {

    @Autowired
    private RedisTemplate redisTemplate;

    @Test
    void contextLoads() {
    }


    @Test
    void testString(){
        redisTemplate.opsForValue().set("name","zhangsan");
        Object name = redisTemplate.opsForValue().get("name");
        System.out.println("name = " + name);
    }

    @Test
    void testUser(){
        redisTemplate.opsForValue().set("user::1", User.builder().id(1).name("zhangsan").age(20).build());
        Object name = redisTemplate.opsForValue().get("user::1");
        System.out.println("name = " + name);
    }


    /**
     * SpringDataRedis提供了RedisTemplate的子类：StringRedisTemplate的key和value的序列化方式默认就是String方式。
     *  redisTemplate.setValueSerializer(RedisSerializer.string());
     *  redisTemplate.setHashValueSerializer(RedisSerializer.string());
     */
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    private static final ObjectMapper mapper = new ObjectMapper();
    @Test
    void testUserStringJson() throws JsonProcessingException {
        User user = User.builder().id(1).name("zhangsan").age(20).build();
        // 手动序列化
        String json = mapper.writeValueAsString(user);
        stringRedisTemplate.opsForValue().set("user::1", json);
        String jsonUser = stringRedisTemplate.opsForValue().get("user::1");
        User readUser = mapper.readValue(jsonUser, User.class);

        System.out.println("user = " + readUser);
    }

}
