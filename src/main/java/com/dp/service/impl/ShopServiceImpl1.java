package com.dp.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.dp.dto.Result;
import com.dp.entity.Shop;
import com.dp.mapper.ShopMapper;
import com.dp.service.ShopService;
import com.dp.utils.redis.CacheRedisUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.util.concurrent.TimeUnit;

import static com.dp.utils.SystemConstants.MAX_PAGE_SIZE;
import static com.dp.utils.redis.RedisConstants.*;

/**
 * 使用自定义封装的Redis工具类
 */
@Slf4j
//@Service
public class ShopServiceImpl1 extends ServiceImpl<ShopMapper, Shop> implements ShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    // 工具类
    @Resource
    private CacheRedisUtil cacheRedisUtil;

    @Override
    public Result queryById(Long id) {
        // 使用自定义工具类：解决缓存穿透（使用空值）
//        Shop shop = cacheRedisUtil.queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, this::getById, RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);

        // 互斥锁解决缓存击穿
//        Shop shop = cacheRedisUtil.queryWithMutex(CACHE_SHOP_KEY, LOCK_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);

        // 使用自定义工具类：解决热点数据缓存击穿（使用逻辑过期时间）【需要提前加载数据即预热，DianPingApplicationTest类】
        Shop shop = cacheRedisUtil.queryWithLogicalExpire(CACHE_SHOP_KEY, LOCK_SHOP_KEY, id, Shop.class, this::getById, 20L, TimeUnit.SECONDS);
        if (shop == null) {
            return Result.fail("商铺不存在");
        }
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

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        return null;
    }
}

