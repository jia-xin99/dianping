package com.dp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.dp.dto.Result;
import com.dp.entity.Shop;

public interface ShopService extends IService<Shop> {
    Result queryById(Long id);
}
