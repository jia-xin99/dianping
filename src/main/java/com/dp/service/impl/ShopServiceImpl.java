package com.dp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.dp.dto.Result;
import com.dp.entity.Shop;
import com.dp.mapper.ShopMapper;
import com.dp.service.ShopService;
import com.dp.utils.redis.RedisData;
import jodd.util.StringUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.*;

import static com.dp.utils.SystemConstants.MAX_PAGE_SIZE;
import static com.dp.utils.redis.RedisConstants.*;

@Slf4j
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements ShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = new ThreadPoolExecutor(
            10,
            10,
            0L,
            TimeUnit.MILLISECONDS,
            new LinkedBlockingDeque<>(300),
            Executors.defaultThreadFactory(),
            new ThreadPoolExecutor.DiscardOldestPolicy());

    /**
     * 解决缓存击穿问题
     * 根据id查询店铺，第四版本：使用逻辑删除
     */
    public Result queryById4(Long id) {
        Shop shop = queryWithLogicalExpire(id);
        if (shop == null) {
            return Result.fail("商铺不存在");
        }
        return Result.ok(shop);
    }


    /**
     * 缓存击穿（使用逻辑过期）：需提前导入热点数据
     * 思路：如某时间段的热点商品，提前加入热点数据，如数据不存在，直接返回，数据存在，判断是否过期
     * 想法：万一该预热商品更新数据时，得考虑是删除缓存还是对商品信息缓存重建。
     * 此处针对的是热点数据，不是所有数据
     */
    public Shop queryWithLogicalExpire(Long id) {
        String key = CACHE_SHOP_KEY + id;
        // 1. 从redis中查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 2. 判断是否存在
        if (StrUtil.isBlank(shopJson)) {
            // redis中不存在，则返回
            return null;
        }
        // 3. 缓存命中，JSON反序列化成对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        Shop shop = JSONUtil.toBean(data, Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        // 4. 判断数据是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            //  数据未过期，返回店铺信息
            return shop;
        }
        // 5. 数据过期，更新缓存数据
        // 6. 尝试获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        // 6.1 获取成功
        if (isLock) {
            // 注意：获取锁成功应该再次检测redis缓存是否过期，做DoubleCheck，如果存在则无需重建缓存。
            // DoubleCheck：请求1刚刚把Redis数据更新为新数据并释放了锁，请求2查询到旧数据后拿到锁，就得需要再次查询Redis看是否过期，减少与数据库交互的可能次数。
            String shopJson1 = stringRedisTemplate.opsForValue().get(key);
            if (StringUtil.isBlank(shopJson1)) {
                return null;
            }
            RedisData redisData1 = JSONUtil.toBean(shopJson1, RedisData.class);
            LocalDateTime expireTime1 = redisData.getExpireTime();
            if (expireTime1.isAfter(LocalDateTime.now())) {
                return JSONUtil.toBean((JSONObject) redisData1.getData(), Shop.class);
            }
            // 7. 开启新线程进行缓存重建（使用线程池）
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    // 8. 查询数据库数据
                    // 9. 更新缓存数据到redis中
                    saveShopRedis(id, 20L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    // 10. 释放锁
                    unlock(lockKey);
                }
            });
        }
        // 6.2. 获取失败，返回旧数据
        // 返回旧数据
        return shop;
    }

    /**
     * 作用1：模拟商铺热点数据预热（给使用逻辑过期策略解决缓存击穿问题做前提准备，前提是执行单元测试中的预热方法【见DianPingApplicationTest】）
     * 作用2：缓存重建
     */
    public void saveShopRedis(Long id, Long expireSeconds) throws InterruptedException {
        // 1. 查询店铺数据
        Shop shop = getById(id);
        if (shop == null) {
            return;
        }
        // 模拟缓存重建的时间
        Thread.sleep(200);
        // 2. 封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        // 3. 写入redis，不需要设置过期时间（有逻辑过期时间）
        String key = CACHE_SHOP_KEY + id;
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    /**
     * 解决缓存击穿问题
     * 根据id查询店铺，第三版：使用互斥锁
     */
    @Override
    public Result queryById(Long id) {
        Shop shop = queryWithMutex(id);
        if (shop == null) {
            return Result.fail("商铺不存在");
        }
        return Result.ok(shop);
    }

    /**
     * 缓存击穿（使用互斥锁）
     */
    public Shop queryWithMutex(Long id) {
        String key = CACHE_SHOP_KEY + id;
        // 1. 查询缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(shopJson)) {
            // 2. 缓存命中返回数据
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        // 3. 缓存未命中
        // 3.1 缓存是否是否是空值
        if (shopJson != null) {
            return null;
        }
        // 4. 实现缓存重建
        // 4.1 尝试获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        Shop shop = null;
        // 4.2 判断是否获取成功
        boolean isLock = tryLock(lockKey);
        if (isLock) {
            try {
                // 4.4 成功，根据id查询数据库
                shop = getById(id);
                // 模拟延迟时间
                Thread.sleep(200);
                if (shop == null) {
                    // 不存在，将空值写入redis
                    stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                    return null;
                }
                // 存在，写入redis
                stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            } finally {
                // 7. 释放互斥锁
                unlock(lockKey);
            }
        } else {
            // 4.3 失败，休眠并等待重试
            try {
                Thread.sleep(50);
                return queryWithMutex(id);
            } catch (InterruptedException ex) {
                ex.printStackTrace();
            }
        }
        //  返回
        return shop;
    }

    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }

    /**
     * 解决缓存穿透问题
     * 根据id查询店铺，第二版：先从redis查缓存，若无再查Mysql，mysql也无则赋空值至redis
     */
    public Result queryById2(Long id) {
        Shop shop = queryWithPassThrough(id);
        if (shop == null) {
            return Result.fail("商铺不存在");
        }
        return Result.ok(shop);
    }

    /**
     * 缓存穿透（使用空值）
     */
    private Shop queryWithPassThrough(Long id) {
        String key = CACHE_SHOP_KEY + id;
        // 1. 从redis中查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 2. 判断缓存中是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            // 存在，则返回
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        // 3. 判断缓存是否是空值
        if (shopJson != null) {
            return null;
        }
        // 4. 不存在，根据id查询数据库
        Shop shop = getById(id);
        if (shop == null) {
            // 不存在，则设置空值到redis中，过期时间比正常数据短
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        // 5. 数据库中存在数据，将商铺信息写入redis，并设置超时时间，避免redis缓存过多数据
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        // 6. 返回
        return shop;
    }

    /**
     * 根据id查询店铺，第一版：先从redis查缓存，若无再查Mysql
     */
    public Result queryById1(Long id) {
        String cacheShopKey = CACHE_SHOP_KEY + id;
        // 1. 从Redis中查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(cacheShopKey);
        // 2. 判断缓存是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            // 缓存存在
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return Result.ok(shop);
        }
        // 3. 缓存未命中，根据id查询数据库
        Shop shop = this.getById(id);
        if (shop == null) {
            // 数据库中不存在数据，返回错误
            return Result.fail("商铺不存在");
        }
        // 4. 数据库中存在数据，将商铺信息写入redis，并设置超时时间，避免redis缓存过多数据
        stringRedisTemplate.opsForValue().set(cacheShopKey, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        // 5. 返回商铺信息
        return Result.ok(shop);
    }

    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺id不可为空");
        }
        // 1. 更新数据库
        boolean success = updateById(shop);
        // 2. 删除缓存
        if (success) {
            String cacheShopKey = CACHE_SHOP_KEY + shop.getId();
            stringRedisTemplate.delete(cacheShopKey);
        }
        return Result.ok();
    }

    @Override
    public Result queryShopByName(String name, Integer current) {
        LambdaQueryWrapper<Shop> queryWrapper = new LambdaQueryWrapper<>();
        // 根据名字分页查询
        queryWrapper.like(StrUtil.isNotBlank(name), Shop::getName, name);
        Page<Shop> shopPage = new Page<>(current, MAX_PAGE_SIZE);
        shopPage = this.page(shopPage, queryWrapper);
        return Result.ok(shopPage.getRecords());
    }
}

