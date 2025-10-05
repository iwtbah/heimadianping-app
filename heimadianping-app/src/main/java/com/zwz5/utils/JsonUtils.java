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

    public <T> List<T> jsonToList(String json, Class<T> clazz) {
        try {
            return objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, clazz));
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }
}