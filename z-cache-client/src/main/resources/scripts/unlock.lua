-- unlock.lua — z-cache 分布式锁原子解锁脚本
--
-- KEYS[1] = lock key
-- ARGV[1] = expected ownerId
-- ARGV[2] = expected fencingToken（0 表示不校验 fencing token）
--
-- 返回值：
--   1  = 解锁成功
--  -1  = 锁不存在（已过期或从未持有）
--  -2  = value 格式异常（不含 | 分隔符）
--  -3  = ownerId 不匹配（不是自己的锁）
--  -4  = fencing token 过期（已被更高 token 的请求作废）

local current = redis.call('GET', KEYS[1])
if current == false then
    return -1
end

local sep = string.find(current, '|', 1, true)
if not sep then
    return -2
end

local curOwner = string.sub(current, 1, sep - 1)
local curToken = tonumber(string.sub(current, sep + 1))

if curOwner ~= ARGV[1] then
    return -3
end

if tonumber(ARGV[2]) > 0 and curToken < tonumber(ARGV[2]) then
    return -4
end

redis.call('DEL', KEYS[1])
return 1
