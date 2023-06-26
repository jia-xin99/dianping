package com.dp;

import com.dp.entity.Shop;
import com.dp.service.ShopService;
import com.dp.utils.redis.CacheRedisUtil;
import com.dp.utils.redis.RedisIdWorker;
import com.sun.org.apache.xml.internal.utils.StringToStringTable;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.dp.utils.redis.RedisConstants.CACHE_SHOP_KEY;
import static com.dp.utils.redis.RedisConstants.SHOP_GEO_KEY;

@SpringBootTest
public class DianPingApplicationTest {
    @Resource
    private ShopService shopService;

    @Resource
    private CacheRedisUtil cacheRedisUtil;

    @Resource
    private RedisIdWorker idWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

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


    /**
     * 加载商店的地理位置到Redis中
     */
    @Test
    public void loadShopData() {
        // 1. 查询店铺信息
        List<Shop> list = shopService.list();
        // 2. 把店铺分组，按照typeId分组，typeId一样的商铺放在同一个集合中
        Map<Long, List<Shop>> map = list.stream().collect(Collectors.groupingBy(Shop::getTypeId));
        for (Map.Entry<Long, List<Shop>> entry : map.entrySet()) {
            // 同一typeId的shop集合
            Long typeId = entry.getKey();
            List<Shop> shops = entry.getValue();
            // 将shops转换成Map，便于批量添加
            Map<String, Point> shopMap = shops.stream().collect(Collectors.toMap(
                    shop -> shop.getId().toString(),
                    shop -> new Point(shop.getX(), shop.getY())
            ));
            String key = SHOP_GEO_KEY + typeId;
            // 3. 分批写入redis中的geo中
            stringRedisTemplate.opsForGeo().add(key, shopMap);
        }
    }

    /**
     * 测试HyperLogLog
     */
    @Test
    public void testHyperLogLog() {
        String[] users = new String[1000];
        int index = 0;
        for (int i = 0; i < 10000; i++) {
            users[index++] = "user_" + i;
            if (index % 1000 == 0) {
                // 每1000条发一次
                // 重置
                index = 0;
                stringRedisTemplate.opsForHyperLogLog().add("uv_test", users);
            }
        }
        // 基数统计添加的用户数量
        Long count = stringRedisTemplate.opsForHyperLogLog().size("uv_test");
        System.out.println("count: " + count);
    }
}

