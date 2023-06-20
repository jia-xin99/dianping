package com.dp.controller;


import com.dp.dto.Result;
import com.dp.service.ShopTypeService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

@RestController
@RequestMapping("/shop-type")
public class ShopTypeController {
    @Resource
    private ShopTypeService typeService;

    @GetMapping("list")
    public Result queryTypeList() {
        return typeService.getList();
    }
}
