package com.dp.utils.redis;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 缓存数据：添加过期时间字段
 */
@Data
public class RedisData {
    private LocalDateTime expireTime;
    private Object data;
}

