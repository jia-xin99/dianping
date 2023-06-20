package com.dp;

import com.dp.entity.Shop;
import com.dp.service.ShopService;
import com.dp.utils.redis.CacheRedisUtil;
import com.dp.utils.redis.RedisIdWorker;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.dp.utils.redis.RedisConstants.CACHE_SHOP_KEY;

@SpringBootTest
public class DianPingApplicationTest {
    @Resource
    private ShopService shopService;

    @Resource
    private CacheRedisUtil cacheRedisUtil;

    @Resource
    private RedisIdWorker idWorker;

    private ExecutorService es = Executors.newFixedThreadPool(500);

    /**
     * 数据预热
     */
    @Test
    public void test() throws InterruptedException {
//        shopService.saveShopRedis(1L, 10L);
        // 使用自定义redis工具类预热
        Shop shop = shopService.getById(1L);
        cacheRedisUtil.setWithLogicalExpire(CACHE_SHOP_KEY + 1L, shop, 10L, TimeUnit.SECONDS);
    }

    /**
     * 测试并发生成id
     */
    @Test
    public void testIdWorker() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(300);
        Runnable task = () -> {
            for (int i = 0; i < 100; i++) {
                long orderId = idWorker.nextId("order");
                System.out.println("id: " + orderId);
            }
            latch.countDown();
        };
        long begin = System.currentTimeMillis();
        for (int i = 0; i < 300; i++) {
            es.submit(task);
        }
        latch.await();
        long end = System.currentTimeMillis();
        System.out.println("time =" + (end - begin));
    }
}

