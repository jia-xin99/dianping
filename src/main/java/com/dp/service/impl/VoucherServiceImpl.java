package com.dp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.dp.dto.Result;
import com.dp.entity.SeckillVoucher;
import com.dp.entity.Voucher;
import com.dp.mapper.VoucherMapper;
import com.dp.service.SeckillVoucherService;
import com.dp.service.VoucherService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.List;

import static com.dp.utils.redis.RedisConstants.SECKILL_STOCK_KEY;

@Service
public class VoucherServiceImpl extends ServiceImpl<VoucherMapper, Voucher> implements VoucherService {

    @Resource
    private SeckillVoucherService seckillVoucherService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    @Transactional
    public void addSeckillVoucher(Voucher voucher) {
        // 保存优惠券基本信息
        this.save(voucher);
        // 保存秒杀优惠券信息
        // 注意：其实是需要逻辑判断优惠券是否是秒杀优惠券的（添加时间和更新时间在数据库层面自动添加，见建表语句）
        LocalDateTime beginTime = voucher.getBeginTime();
        LocalDateTime endTime = voucher.getEndTime();
        Integer stock = voucher.getStock();
        if (beginTime == null && endTime == null && stock == null) {
            return;
        }
        if (beginTime == null || endTime == null || stock == null || beginTime.isAfter(endTime) || stock <= 0) {
            throw new RuntimeException("秒杀优惠券参数有误");
        }
        SeckillVoucher seckillVoucher = new SeckillVoucher();
        seckillVoucher.setVoucherId(voucher.getId());
        seckillVoucher.setBeginTime(beginTime);
        seckillVoucher.setEndTime(endTime);
        seckillVoucher.setStock(stock);
        // 保存秒杀优惠券
        seckillVoucherService.save(seckillVoucher);
        // 保存秒杀库存到redis中
        stringRedisTemplate.opsForValue().set(SECKILL_STOCK_KEY + voucher.getId(), voucher.getStock().toString());
    }

    @Override
    public Result queryVoucherOfShop(Long shopId) {
        List<Voucher> vouchers = this.baseMapper.queryVoucherOfShop(shopId);
        return Result.ok(vouchers);
    }
}

