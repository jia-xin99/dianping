-- 优惠券id
local voucherId = ARGV[1]
-- 用户id
local userId = ARGV[2]
-- 订单id
local orderId = ARGV[3]
-- 2. 数据Key
-- 2.1 库存key
local stockKey = 'seckill:stock:' .. voucherId
-- 2.2 订单Key
local orderKey = 'seckill:order:' .. voucherId
-- 3. 判断优惠券是否存在（防止在下一步计算的时候会报错：nil和0比较）
if (redis.call('exists', stockKey) == 0) then
    -- 3.1 优惠券不存在，则返回1
    return 1
end
-- 4. 判断库存是否还有剩余
if (tonumber(redis.call('get', stockKey)) <= 0) then
    -- 4.1 库存不足，则返回2
    return 2
end
-- 5. 判断用户是否已下单，判断一人一单
if (redis.call('sismember', orderKey, userId) == 1) then
    -- 5.1 存在，说明是重复下单，则返回3
    return 3
end
-- 可以下单
-- 6. 扣减库存 incrby stockKey -1
redis.call('incrby', stockKey, -1)
-- 7. 添加到已下单set集合中 sadd orderKey userId
redis.call('sadd', orderKey, userId)
-- 前提：在redis中创建一个消费者组
-- 8. 发送消息到消息队列中（stream消费者组） xadd stream.orders * k1 v1 k2 v2
redis.call('xadd', 'stream.orders', '*', 'userId', userId, 'voucherId', voucherId, 'id', orderId)
return 0