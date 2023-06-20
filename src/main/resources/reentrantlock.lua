local key = KEYS[1]; -- 锁的key
local threadId = ARGV[1]; -- 线程唯一标识
local releaseTime = ARGV[2]; -- 锁的自动释放时间
-- 判断锁是否存在
if(redis.call('exists',key) == 0) then
    -- 不存在，则获取锁，创建（重入计数为1）
    redis.call('hset',threadId,1);
    -- 设置有效期
    redis.call('expire',key,releaseTime);
    -- 返回结果
    return 1;
end;
-- 锁存在，则判断加锁对象是否是当前对象
if(redis.call('hexists',key,threadId) == 1) then
    -- 存在，获取锁，重入计数 + 1
    redis.call('hincrby',key,threadId,'1');
    -- 续有效期
    redis.call('expire',key,releaseTime);
    -- 返回结果
    return 1;
end;
return 0; -- 说明获取的锁不是自己，获取锁失败