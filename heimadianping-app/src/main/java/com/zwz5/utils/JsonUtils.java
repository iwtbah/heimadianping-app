package com.zwz5.utils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class JsonUtils {

    private final  ObjectMapper objectMapper; // 复用 Spring 的全局配置
    /**
     * 对象转JSON字符串
     */
    public String beanToJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            log.error("序列化对象失败: {}", obj, e);
            throw new RuntimeException("序列化失败", e);
        }
    }

    /**
     * JSON字符串转对象
     */
    public <T> T jsonToBean(String json, Class<T> clazz) {
        try {
            return objectMapper.readValue(json, clazz);
        } catch (JsonProcessingException e) {
            log.error("反序列化JSON失败: {}", json, e);
            throw new RuntimeException("反序列化失败", e);
        }
    }

    /**
     * 任意对象转目标类型（用于 Map/LinkedHashMap 到实体的转换）
     */
    public <T> T convertValue(Object source, Class<T> clazz) {
        try {
            return objectMapper.convertValue(source, clazz);
        } catch (IllegalArgumentException e) {
            log.error("类型转换失败: source={}, targetType={}", source, clazz, e);
            throw new RuntimeException("类型转换失败", e);
        }
    }

    public <T> List<T> jsonToList(String json, Class<T> clazz) {
        try {
            return objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, clazz));
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }
}
