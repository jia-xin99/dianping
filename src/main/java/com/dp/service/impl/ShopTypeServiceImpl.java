package com.dp.service.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.dp.dto.Result;
import com.dp.entity.ShopType;
import com.dp.mapper.ShopTypeMapper;
import com.dp.service.ShopTypeService;
import com.dp.utils.redis.CacheRedisUtil;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import java.util.List;
import java.util.stream.Collectors;

import static com.dp.utils.redis.RedisConstants.SHOP_TYPE_KEY;
import static com.dp.utils.redis.RedisConstants.SHOP_TYPE_LOCK_KEY;

@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements ShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheRedisUtil cacheRedisUtil;

    @Override
    public Result getList() {
        // 先查缓存
        List<ShopType> typeList = null;
        List<String> jsonList = stringRedisTemplate.opsForList().range(SHOP_TYPE_KEY, 0, -1);
        if (jsonList != null && !jsonList.isEmpty()) {
            typeList = jsonList.stream().map(json -> JSONUtil.toBean(json, ShopType.class)).collect(Collectors.toList());
            return Result.ok(typeList);
        }
        // 缓存没有，查数据库
        if (cacheRedisUtil.tryLock(SHOP_TYPE_LOCK_KEY)) {
            try {
                typeList = this.list();
                List<String> typeListStr = typeList.stream().map(type -> JSONUtil.toJsonStr(type)).collect(Collectors.toList());
                stringRedisTemplate.opsForList().rightPushAll(SHOP_TYPE_KEY, typeListStr);
            } finally {
                cacheRedisUtil.unlock(SHOP_TYPE_LOCK_KEY);
            }
        } else {
            return getList();
        }
        return Result.ok(typeList);
    }
}
