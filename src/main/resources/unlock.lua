-- 锁的key
local key = KEYS[1]
-- 当前线程标识
local threadId = ARGV[1]

-- 1. 获取当前锁中的线程标识
local id = redis.call('get',key)

-- 2. 比较线程标识与锁中标识是否一致
if(id == threadId) then
    -- 3. 如果相同，则说明是当前线程获取的锁，当前线程可释放锁 del
    return redis.call('del',key)
end
-- 4. 不一致，则直接返回
return 0