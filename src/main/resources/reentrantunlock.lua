local key = KEYS[1]; -- 锁的key
local threadId = ARGV[1]; -- 线程唯一标识
local releaseTime = ARGV[2]; -- 锁的自动释放时间
if(redis.call('hexists',key,threadId) == 0) then
    -- 当前锁的对象不是自己，则直接返回
    return nil;
end;
-- 是自己的锁，则重入次数-1
local count = redis.call('hincrby',key,threadId,-1);
-- 判断是否重入次数是否已经为0
if(count > 0) then
    -- 大于0，说明不能释放锁，重置有效期并返回
    redis.call('expire',key,releaseTime);
    return nil;
else -- 等于0说明可以释放锁，直接删除
    redis.call('del',key);
    return nil;
end;

