local key = KEYS[1]
local limit = tonumber(ARGV[1])
local window_size_ms = tonumber(ARGV[2])
local cost = tonumber(ARGV[3])
local now = tonumber(ARGV[4])

-- Set key with TTL only if it doesn't exist yet (NX = only if not exists)
redis.call('SET', key, '0', 'PX', window_size_ms, 'NX')

local count = tonumber(redis.call('GET', key) or '0')
if count + cost > limit then
  local ttl = redis.call('PTTL', key)
  return {0, math.max(0, limit - count), now + math.max(0, ttl)}
end
redis.call('INCRBY', key, cost)
local ttl = redis.call('PTTL', key)
return {1, limit - count - cost, now + math.max(0, ttl)}
