package com.dp;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.weaver.ast.Or;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;

import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;

@Slf4j
@SpringBootTest
public class RedissionTest {

    @Resource
    private RedissonClient redissonClient;

    @Test
    void testRedission() throws InterruptedException {
        // 定义锁对象（可重入），指定锁的名称
        // redission的value值是map类型（对应加锁线程的标识 - 重入次数）
        RLock lock = redissonClient.getLock("anyLock");
        // 尝试获取锁，参数分别是：获取锁的最大等待时间（期间会重试），锁自动释放时间，时间单位
        boolean isLock = lock.tryLock(1, 10, TimeUnit.SECONDS);
        if (isLock) {
            try {
                Thread.sleep(8000);
                System.out.println("执行业务");
            } finally {
                lock.unlock();
            }
        }
    }

    private RLock lock;

    @BeforeEach
    void setUp() {
        lock = redissonClient.getLock("lock");
    }

    /**
     * 测试Redission可重入：key为lock，value为Map类型数据，其中对应于当前加锁线程标识 - 加锁次数
     */
    @Test
    public void method1() {
        boolean isLock = lock.tryLock();
        if (isLock) {
            try {
                Thread.sleep(3000);
                log.info("获取锁 -- 1");
                method2();
            } catch (InterruptedException e) {
                e.printStackTrace();
            } finally {
                log.info("释放锁 -- 1");
                lock.unlock();
            }
        }
    }

    public void method2() {
        try {
            log.info("获取锁 -- 2");
            lock.tryLock();
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        } finally {
            log.info("释放锁 -- 2");
            lock.unlock();
        }
    }

    @Resource
    public RedissonClient redissonClient1;

    @Resource
    public RedissonClient redissonClient2;

    @Resource
    public RedissonClient redissonClient3;

    private RLock rLock;

    @BeforeEach
    void setUp1() {
        RLock lock1 = redissonClient1.getLock("order");
        RLock lock2 = redissonClient2.getLock("order");
        RLock lock3 = redissonClient3.getLock("order");
        // 底层：final List<RLock> locks = new ArrayList<>();
        rLock = redissonClient1.getMultiLock(lock1, lock2, lock3);
    }
}

