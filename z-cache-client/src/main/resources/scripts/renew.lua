-- renew.lua — z-cache 分布式锁原子续约脚本
--
-- KEYS[1] = lock key
-- ARGV[1] = expected ownerId
-- ARGV[2] = new ttlMs（毫秒）
--
-- 返回值：
--   1 = 续约成功
--   0 = 失败（锁不存在 / ownerId 不匹配 / value 格式异常）

local current = redis.call('GET', KEYS[1])
if current == false then
    return 0
end

local sep = string.find(current, '|', 1, true)
if not sep then
    return 0
end

local curOwner = string.sub(current, 1, sep - 1)
if curOwner ~= ARGV[1] then
    return 0
end

redis.call('SET', KEYS[1], current, 'PX', tonumber(ARGV[2]))
return 1
