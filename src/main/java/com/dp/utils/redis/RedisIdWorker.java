package com.dp.utils.redis;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * 基于Redis的ID生成器
 */
@Component
public class RedisIdWorker {

    /**
     * 开始时间戳
     */
    private static final long BEGIN_TIMESTAMP = 1640995200L;
    /**
     * 序列号位数
     */
    private static final int COUNT_BITS = 32;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * ID：1位（符号位） + 31位（时间戳） + 32位（序列号）
     * 一种业务不能用同一个自增key，若数据量庞大否则会超出32位（序列号）
     * 则可使用业务_时间来进行自增（将一个业务的key进一步细分），来给每一天的数据（序列号）进行自增，也方便后续统计
     *
     * @param keyPrefix: 不同业务
     */
    public Long nextId(String keyPrefix) {
        // 1. 生成时间戳
        LocalDateTime now = LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timeStamp = nowSecond - BEGIN_TIMESTAMP;
        // 2. 生成序列号
        // 2.1 获取当前日期，精确到天
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        // 2.2 自增长
        Long count = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + date);
        // 3. 拼接并返回
        // 1位（符号位） + 31位（时间戳） + 32位（序列号）
        return timeStamp << COUNT_BITS | count;
    }
}

