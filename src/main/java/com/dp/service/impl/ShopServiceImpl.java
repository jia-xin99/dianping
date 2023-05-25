package com.dp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.dp.dto.Result;
import com.dp.entity.Shop;
import com.dp.mapper.ShopMapper;
import com.dp.service.ShopService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.management.StringValueExp;

import static com.dp.utils.RedisConstants.CACHE_SHOP_KEY;

@Slf4j
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements ShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryById(Long id) {
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
        // 4. 数据库中存在数据，将商铺信息写入redis
        stringRedisTemplate.opsForValue().set(cacheShopKey, JSONUtil.toJsonStr(shop));
        // 5. 返回商铺信息
        return Result.ok(shop);
    }
    // TODO 店铺类型List查询，缓存起来

}

