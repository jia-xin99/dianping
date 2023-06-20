package com.dp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.dp.dto.Result;
import com.dp.entity.Voucher;

public interface VoucherService extends IService<Voucher> {

    void addSeckillVoucher(Voucher voucher);

    Result queryVoucherOfShop(Long shopId);
}

