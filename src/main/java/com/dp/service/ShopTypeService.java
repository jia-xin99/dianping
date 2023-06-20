package com.dp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.dp.dto.Result;
import com.dp.entity.ShopType;

public interface ShopTypeService extends IService<ShopType> {

    Result getList();
}
