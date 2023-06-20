package com.dp.utils.redis;

import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

/**
 * 简单实现redis分布式锁
 */
public class SimpleRedisLock implements ILock {

    private StringRedisTemplate stringRedisTemplate;
    private String name;


    private static final String ID_PREFIX = UUID.randomUUID().toString(true) + "-";
    private static final String KEY_PREFIX = "lock:";
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;

    // 提前初始化Lua脚本
    static {
        UNLOCK_SCRIPT = new DefaultRedisScript();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        // 设置脚本返回值的类型
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    public SimpleRedisLock(StringRedisTemplate stringRedisTemplate, String name) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.name = name;
    }

    /**
     * 改进分布式锁1-加锁：锁的释放只能由自己线程进行释放。则：key-value：业务标识+id-线程标识
     * 一人一单中：一人请求多次会被不同线程处理，使用的是同一名称的分布式锁，但是只允许释放本线程请求的分布式锁
     */
    @Override
    public boolean tryLock(Long timeoutSec) {
        // 获取线程标识
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        // 获取锁
        String key = KEY_PREFIX + name;
        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(key, threadId, timeoutSec, TimeUnit.SECONDS);
        // 防止有null
        return Boolean.TRUE.equals(success);
    }

    /**
     * 改进分布式锁1-解锁：锁的释放只能由自己线程进行释放。则：key-value：业务标识+id-线程标识
     * 释放锁之前判断加锁的是不是本线程。
     */
    public void unlock2() {
        String key = KEY_PREFIX + name;
        // 获取当前分布式锁的value值
        String value = stringRedisTemplate.opsForValue().get(key);
        // 获取线程id
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        if (threadId.equals(value)) {
            // 释放锁
            stringRedisTemplate.delete(key);
        }
    }

    /**
     * 改进分布式锁2-解锁：
     * 释放锁之前要查询锁是否是本线程，有两个redis请求，将判断锁和释放锁的过程放在一个Lua脚本中，就只需要一个请求即可。
     */
    @Override
    public void unlock() {
        // 获取锁
        String key = KEY_PREFIX + name;
        // 获取线程id
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        // 使用lua脚本进行锁的判断和释放
        // 等价于EVAL script keys args
        stringRedisTemplate.execute(UNLOCK_SCRIPT, Collections.singletonList(key), threadId);
    }

    public boolean tryLock1(Long timeoutSec) {
        // 获取锁
        String key = KEY_PREFIX + name;
        long threadId = Thread.currentThread().getId();
        String value = threadId + "";
        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(key, value, timeoutSec, TimeUnit.SECONDS);
        // 防止有null
        return Boolean.TRUE.equals(success);
    }

    public void unlock1() {
        // 释放锁
        String key = KEY_PREFIX + name;
        stringRedisTemplate.delete(key);
    }
}

