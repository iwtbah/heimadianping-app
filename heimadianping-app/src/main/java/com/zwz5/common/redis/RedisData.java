package com.zwz5.common.redis;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class RedisData {
    private LocalDateTime expireTime;
    private Object data;
}
