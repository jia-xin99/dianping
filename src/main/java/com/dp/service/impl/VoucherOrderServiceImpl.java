package com.dp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.dp.dto.Result;
import com.dp.entity.SeckillVoucher;
import com.dp.entity.VoucherOrder;
import com.dp.mapper.VoucherOrderMapper;
import com.dp.service.SeckillVoucherService;
import com.dp.service.VoucherOrderService;
import com.dp.utils.redis.RedisIdWorker;
import com.dp.utils.redis.SimpleRedisLock;
import com.dp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.dp.utils.redis.RedisConstants.LOCK_ORDER_KEY;

@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements VoucherOrderService {

    @Resource
    private SeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private RedissonClient redissonClient;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private final static DefaultRedisScript<Long> SECKILL_SCRIPT;
    private final static DefaultRedisScript<Long> SECKILL_SCRIPT1;
    private VoucherOrderService proxy;
    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    static {
        SECKILL_SCRIPT = new DefaultRedisScript();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);

        SECKILL_SCRIPT1 = new DefaultRedisScript();
        SECKILL_SCRIPT1.setLocation(new ClassPathResource("seckill1.lua"));
        SECKILL_SCRIPT1.setResultType(Long.class);
    }

    /**
     * 下单秒杀优惠券：版本5：优惠券库存和一人一单记录保存在Redis中，然后下单信息保存在Redis的消息队列中，然后让一个线程池去处理。
     * （TODO 待优化：在优惠券入库时存储hash结构，主要涉及优惠券的库存、开始结束时间，可在lua中进行逻辑判断）
     *
     * @param voucherId: 秒杀优惠券Id
     */
    @Override
    public Result seckillVoucher(Long voucherId) {
        // 获取用户id
        Long userId = UserHolder.getUser().getId();
        // 订单id
        Long orderId = redisIdWorker.nextId("order");
        // 执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT1,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(), String.valueOf(orderId));
        // 判断脚本返回值，返回结果即可，加入消息队列操作已在lua脚本中完成。
        int r = result.intValue();
        switch (r) {
            case 1:
                return Result.fail("优惠券不存在");
            case 2:
                return Result.fail("优惠券库存不足");
            case 3:
                return Result.fail("不可重复下单");
            default:
                // 注意：此处是为方便在线程池执行消息队列任务时可以使用代理类执行事务方法
                proxy = (VoucherOrderService) AopContext.currentProxy();
                return Result.ok(orderId);
        }
    }

    @PostConstruct
    private void init1() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler1());
    }

    private class VoucherOrderHandler1 implements Runnable {

        private String queueName = "stream.orders";

        @Override
        public void run() {
            while (true) {
                try {
                    // 1. 从redis stream流中获取下单消息 XREADGROUP g1 c1 COUNT 1 BLOCK
                    // --- g1：消费组组名，c1：消费者名
                    // --- 每次读取1条信息，阻塞2秒
                    // --- stream.orders：key名，lastConsumed()里面是'>'：读取最新未消费的消息
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed()));
                    // 2. 判断消息是否成功
                    // 2.1 获取失败，说明没有消息，继续下一个循环
                    if (list == null || list.isEmpty()) {
                        continue;
                    }
                    // 2.2 获取成功
                    // 3. 解析消息中信息
                    MapRecord<String, Object, Object> record = list.get(0);
                    RecordId recordId = record.getId();
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    // 4. 创建订单
                    handleVoucherOrder(voucherOrder);
                    // 5. ack消息
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", recordId);
                } catch (Exception e) {
                    // 6. 有异常，则处理未消费的消息（pending list）
                    log.error("发现订单异常", e);
                    handlerPendingList();
                }
            }
        }

        private void handlerPendingList() {
            while (true) {
                try {
                    // 1. 获取pending-list中的订单消息 XREADGROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS s1 0
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.from("0")));
                    // 2. 判断订单消息是否为空
                    // 2.1 消息为空，则说明异常消息已经处理完，退出循环
                    if (list == null || list.isEmpty()) {
                        break;
                    }
                    // 2.2 消息不为空，处理消息
                    MapRecord<String, Object, Object> record = list.get(0);
                    // 3. 解析消息信息
                    RecordId recordId = record.getId();
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    // 4. 下单
                    handleVoucherOrder(voucherOrder);
                    // 5. ack消息
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", recordId);
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                }
            }
        }
    }

    /**
     * 下单秒杀优惠券：版本4：优惠券库存和一人一单记录保存在Redis中。之后将下单成功信息保存在阻塞队列中，让线程池去处理
     * （TODO 待优化：在优惠券入库时存储hash结构，主要涉及优惠券的库存、开始结束时间，可在lua中进行逻辑判断）
     *
     * @param voucherId: 秒杀优惠券Id
     */
    public Result seckillVoucher4(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        // 1. 执行Lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString());
        // 2. 返回值判断：
        int r = result.intValue();
        // 1-库存不足 2-已下过单
        if (r != 0) {
            switch (r) {
                case 1:
                    return Result.fail("优惠券不存在");
                case 2:
                    return Result.fail("优惠券库存不足");
                case 3:
                    return Result.fail("不可重复下单");
            }
        }
        // 注意：此处是为方便在线程池执行阻塞队列任务时可以使用代理类执行事务方法
        proxy = (VoucherOrderService) AopContext.currentProxy();
        // 3. 0-下单成功，下单信息加入到阻塞队列中
        VoucherOrder voucherOrder = new VoucherOrder();
        // 3.1 订单信息（订单id使用Id生成器生成）
        Long orderId = redisIdWorker.nextId("order");
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);
        voucherOrder.setId(orderId);
        // 3.2 保存订单信息到阻塞队列中
        orderTasks.add(voucherOrder);
        // 返回订单id
        return Result.ok(orderId);
    }

    //    @PostConstruct
    public void init() {
        log.info("处理阻塞队列消息已开启");
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    // 处理阻塞队列中的消息，程序启动后就开始执行
    private class VoucherOrderHandler implements Runnable {
        @Override
        public void run() {
            while (true) {
                try {
                    // 1.获取队列中的订单信息
                    VoucherOrder order = orderTasks.take();
                    // 2.创建订单，将订单保存在数据库中
                    handleVoucherOrder(order);
                } catch (InterruptedException e) {
                    log.error("处理订单异常", e);
                }
            }
        }
    }

    // 保存订单到数据库中
    // 兜底加锁还是防止一人多单的问题（虽然在前面已经能基本防止）
    private void handleVoucherOrder(VoucherOrder order) {
        // 1. 获取用户
        Long userId = order.getUserId();
        // 2.创建锁对象
        RLock lock = redissonClient.getLock(LOCK_ORDER_KEY + userId);
        // 3.获取锁
        boolean isLock = lock.tryLock();
        // 4.判断是否获取锁成功
        // 5.1 获取锁成功
        if (isLock) {
            try {
                proxy.createVoucherOrder(order);
                return;
            } finally {
                // 6. 释放锁
                lock.unlock();
            }
        }
        // 5.2 获取锁失败
        log.error("不允许重复下单");
        return;
    }

    @Transactional
    @Override
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        //1. 获取基本信息
        Long userId = voucherOrder.getUserId();
        Long voucherId = voucherOrder.getVoucherId();
        // 3. 判断是否已经下单（一人一单）
        LambdaQueryWrapper<VoucherOrder> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(VoucherOrder::getUserId, userId)
                .eq(VoucherOrder::getVoucherId, voucherId);
        int count = this.count(queryWrapper);
        if (count > 0) {
            log.error("用户已购买一次");
            return;
        }
        // 4. 尝试扣减库存
        LambdaUpdateWrapper<SeckillVoucher> updateWrapper = Wrappers.<SeckillVoucher>lambdaUpdate()
                .eq(SeckillVoucher::getVoucherId, voucherId)
                .gt(SeckillVoucher::getStock, 0)
                .setSql("stock = stock - 1");
        boolean success = seckillVoucherService.update(updateWrapper);
        // 4. 若扣减成功，则创建订单
        if (success) {
            this.save(voucherOrder);
            log.info("创建订单成功");
            return;
        }
        log.error("库存不足");
    }

    /**
     * 下单秒杀优惠券：版本3：使用Redission
     *
     * @param voucherId: 秒杀优惠券Id
     */
    public Result seckillVoucher3(Long voucherId) {
        // 1. 查优惠券
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        // 2. 校验是否可以抢优惠券
        if (voucher == null) {
            return Result.fail("优惠券不存在");
        }
        if (voucher.getStock() <= 0) {
            return Result.fail("优惠券库存不足");
        }
        if (voucher.getBeginTime().isAfter(LocalDateTime.now()) || voucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("不在秒杀时间");
        }
        // 创建一人一单（使用分布式锁，防止一人多单）
        Long userId = UserHolder.getUser().getId();
        RLock lock = redissonClient.getLock(LOCK_ORDER_KEY + userId);
        boolean isLock = lock.tryLock();
        if (isLock) {
            // 获取锁成功
            try {
                // 获取代理对象（事务）
                VoucherOrderService proxy = (VoucherOrderService) AopContext.currentProxy();
                // 5.2 创建订单
                return proxy.createVoucherOrder(voucherId);
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                // 5.3 释放锁
                lock.unlock();
            }
        }
        // 获取锁失败，返回错误或重试
        return Result.fail("不允许重复下单");
    }

    /**
     * 下单秒杀优惠券：版本2：使用Redis分布式锁解决集群中一人一单问题
     *
     * @param voucherId: 秒杀优惠券Id
     */
    public Result seckillVoucher2(Long voucherId) {
        // 1. 查询优惠券
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        // 2. 判断优惠券是否存在
        if (voucher == null) {
            return Result.fail("优惠券不存在");
        }
        // 3. 判断秒杀是否开始/结束
        if (voucher.getBeginTime().isAfter(LocalDateTime.now()) || LocalDateTime.now().isAfter(voucher.getEndTime())) {
            return Result.fail("不在秒杀时间");
        }
        // 4. 判断库存是否充足
        if (voucher.getStock() < 1) {
            return Result.fail("优惠券库存不足");
        }
        // 5. 创建一人一单（防止一人多单）
        Long userId = UserHolder.getUser().getId();
        // 5.1 使用分布式锁
        SimpleRedisLock lock = new SimpleRedisLock(stringRedisTemplate, LOCK_ORDER_KEY + userId);
        if (lock.tryLock(20L)) {
            // 获取锁成功
            try {
                VoucherOrderService proxy = (VoucherOrderService) AopContext.currentProxy();
                // 5.2 创建订单
                return proxy.createVoucherOrder(voucherId);
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                // 5.3 释放锁
                lock.unlock();
            }
        }
        // 获取锁失败，返回错误或重试
        return Result.fail("不允许重复下单");
    }

    /**
     * 下单秒杀优惠券：版本1：使用JVM同步锁解决单体中一人一单问题
     *
     * @param voucherId: 秒杀优惠券Id
     * @author jiaxin
     */
    public Result seckillVoucher1(Long voucherId) {
        // 1. 查询优惠券
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        // 2. 判断优惠券是否存在
        if (voucher == null) {
            return Result.fail("优惠券不存在");
        }
        // 3. 判断秒杀是否开始/结束
        if (voucher.getBeginTime().isAfter(LocalDateTime.now()) || LocalDateTime.now().isAfter(voucher.getEndTime())) {
            return Result.fail("不在秒杀时间");
        }
        // 4. 判断库存是否充足
        if (voucher.getStock() < 1) {
            return Result.fail("优惠券库存不足");
        }
        // 创建一人一单
        Long userId = UserHolder.getUser().getId();
        // 问题1：为缩小同步锁锁粒度，避免同一个人多次操作即可锁userId。
        // 问题2：使用userId,toString().intern()原因：拿到常量池中该字符串的引用，而userId.toString()每次是创建了新对象
        // 问题3：synchronized放在该事务方法外的原因：
        // ---若放在事务方法内，Spring事务是在锁释放后才提交，就使得同一个人还是可以多次创建订单。
        // ---就得事务提交后才释放锁
        synchronized (userId.toString().intern()) {
            // 问题4：不直接使用createVoucherOrder而是使用注入的voucherOrderService调用
            // ---涉及到事务失效，直接使用是直接调用原方法
            VoucherOrderService proxy = (VoucherOrderService) AopContext.currentProxy(); // 获取当前代理对象
            return proxy.createVoucherOrder(voucherId);
        }
    }

    @Transactional
    @Override
    public Result createVoucherOrder(Long voucherId) {
        // 5. 一人一单（唯一索引：用户id-优惠券id），保证每个用户只能买一张优惠券--- 创建操作（使用）--使用同步锁机制（避免一个用户抢到多张优惠券）
        Long userId = UserHolder.getUser().getId();
        // 6.1 查询订单，看用户是否购买
        LambdaQueryWrapper<VoucherOrder> orderQueryWrapper = new LambdaQueryWrapper<>();
        orderQueryWrapper.eq(VoucherOrder::getUserId, userId).eq(VoucherOrder::getVoucherId, voucherId);
        int count = this.count(orderQueryWrapper);
        if (count > 0) {
            // 订单存在，用户已购买优惠券，则返回
            return Result.fail("您已抢购该优惠券");
        }
        // 7. 扣减库存（使用CAS法乐观锁）---更新操作
        /*
         xxx set stock = stock - 1  where stock = xx and voucherId = xx --- 容易出现问题：少卖，抢购成功率太低
            voucher.setStock(voucher.getStock() - 1);
            LambdaQueryWrapper<SeckillVoucher> voucherQueryWrapper = new LambdaQueryWrapper<>();
            voucherQueryWrapper.eq(SeckillVoucher::getVoucherId, voucherId)
                   .eq(SeckillVoucher::getStock, voucher.getStock());
            boolean success = seckillVoucherService.update(voucher,voucherQueryWrapper);
         */
        //xxx set stock = stock - 1  where stock > 0 and voucherId = xx--- 改进
        LambdaUpdateWrapper<SeckillVoucher> updateWrapper = Wrappers.<SeckillVoucher>lambdaUpdate()
                .eq(SeckillVoucher::getVoucherId, voucherId)
                .gt(SeckillVoucher::getStock, 0)
                .setSql("stock = stock - 1");
        boolean success = seckillVoucherService.update(updateWrapper);
        if (!success) {
            return Result.fail("优惠券库存不足");
        }
        // 8. 创建订单
        VoucherOrder order = new VoucherOrder();
        // 8.1 订单id（使用Id生成器生成）
        Long orderId = redisIdWorker.nextId("order");
        order.setId(orderId);
        // 8.2 用户id
        order.setUserId(userId);
        // 8.3 代金券id
        order.setVoucherId(voucherId);
        this.save(order);
        // 返回订单id
        return Result.ok(orderId);
    }
}

