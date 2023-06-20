package com.dp.utils.redis;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.dp.utils.redis.RedisConstants.*;

@Slf4j
@Component
public class CacheRedisUtil {

    private final StringRedisTemplate stringRedisTemplate;

    private final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    public CacheRedisUtil(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 方法1：将任意Java对象序列化成JSON并存储在String类型的key中，并可设置TTL过期时间
     */
    public void set(String key, Object value, Long timeout, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), timeout, unit);
    }

    /**
     * 方法2：将任意Java对象序列化成JSON并存储在String类型的key中，并可设置逻辑过期时间，用于解决缓存击穿问题
     */
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        // 设置逻辑过期
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        // 写入redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    /**
     * 方法3：根据指定Key查询缓存，并反序列化为指定类型，利用缓存空值的方式解决缓存穿透问题
     *
     * @param keyPrefix:  key前缀
     * @param id:         key拼接的id
     * @param type:       返回值类型
     * @param dbFallback: 函数式接口，数据库查询结果（根据ID查询到一个为R类型的对象）【可类比匿名对象】
     * @param time:       缓存过期时间
     * @param unit:       时间单位
     */
    public <R, ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        // 拼接key
        String key = keyPrefix + id;
        // 查询redis
        String json = stringRedisTemplate.opsForValue().get(key);
        // 判断redis
        if (StrUtil.isNotBlank(json)) {
            // 存在，就反序列化
            return JSONUtil.toBean(json, type);
        }
        if (json != null) {
            // 空值
            return null;
        }
        // 查询数据库结果
        R dbData = dbFallback.apply(id);
        if (dbData == null) {
            // 不存在，则设置空值到redis中，过期时间比正常数据短
            this.set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
        }
        // 数据库中存在数据，将信息写入redis，并设置超时时间，避免redis缓存过多数据
        this.set(key, dbData, time, unit);
        return dbData;
    }

    /**
     * 方法4：根据指定Key查询缓存，并反序列化为指定类型，利用逻辑过期解决缓存击穿问题
     *
     * @param keyPrefix:     key前缀
     * @param id:            唯一标识
     * @param lockKeyPrefix: 互斥锁前缀
     * @param type:          返回值类型
     * @param dbFallback:    函数式接口，数据库查询结果（根据ID查询到一个为R类型的对象）【可类比匿名对象】
     * @param time:          缓存逻辑过期时间
     * @param unit:          时间单位
     */
    public <R, ID> R queryWithLogicalExpire(String keyPrefix, String lockKeyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        // 从redis中查询数据
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isBlank(json)) {
            // 缓存中不存在
            return null;
        }
        // 缓存中存在，判断是否过期
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        R value = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        if (expireTime.isAfter(LocalDateTime.now())) {
            // 未过期
            return value;
        }
        // 过期，需要缓存重建
        String lockKey = lockKeyPrefix + id;
        if (tryLock(lockKey)) {
            // 获取到同步锁
            // DoubleCheck
            String json1 = stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isBlank(json1)) {
                return null;
            }
            RedisData redisData1 = JSONUtil.toBean(json1, RedisData.class);
            LocalDateTime expireTime1 = redisData1.getExpireTime();
            if (expireTime1.isAfter(LocalDateTime.now())) {
                return JSONUtil.toBean((JSONObject) redisData.getData(), type);
            }
            // 使用线程池进行缓存重建
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    // 查询数据库
                    R r = dbFallback.apply(id);
                    if (r == null) {
                        return;
                    }
                    // 模拟缓存重构时间
                    Thread.sleep(10000);
                    // 更新redis中数据过期时间
                    this.setWithLogicalExpire(key, r, time, unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    // 释放锁
                    unlock(lockKey);
                }
            });
        }
        // 返回旧数据
        return value;
    }

    /**
     * 方法4：根据指定Key查询缓存，并反序列化为指定类型，利用互斥锁解决缓存击穿问题
     *
     * @param keyPrefix:     key前缀
     * @param id:            唯一标识
     * @param lockKeyPrefix: 互斥锁前缀
     * @param type:          返回值类型
     * @param dbFallback:    函数式接口，数据库查询结果（根据ID查询到一个为R类型的对象）【可类比匿名对象】
     * @param time:          缓存逻辑过期时间
     * @param unit:          时间单位
     */
    public <ID, R> R queryWithMutex(String keyPrefix, String lockKeyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        // 从redis中查询数据
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(json)) {
            return JSONUtil.toBean(json, type);
        }
        if (json != null) {
            // 空值
            return null;
        }
        // 尝试加互斥锁
        R data = null;
        String lockKey = lockKeyPrefix + id;
        if (tryLock(lockKey)) {
            try {
                data = dbFallback.apply(id);
                if (data == null) {
                    // 不存在，将空值写入redis
                    stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                    return null;
                }
                // 存在，写入redis
                this.set(key, data, time, unit);
            } finally {
                // 释放互斥锁
                unlock(lockKey);
            }
        } else {
            // 未抢到锁，睡眠后重试
            try {
                Thread.sleep(50);
                return queryWithMutex(keyPrefix, lockKeyPrefix, id, type, dbFallback, time, unit);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        return data;
    }

    public boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    public void unlock(String key) {
        stringRedisTemplate.delete(key);
    }
}

