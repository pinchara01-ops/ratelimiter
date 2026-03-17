local key = KEYS[1]
local limit = tonumber(ARGV[1])
local window_ms = tonumber(ARGV[2])
local cost = tonumber(ARGV[3])
local now = tonumber(ARGV[4])
local window_start = now - window_ms

-- Exclusive lower bound: events at exactly window_start are still within window
redis.call('ZREMRANGEBYSCORE', key, '-inf', '(' .. window_start)
local count = tonumber(redis.call('ZCARD', key))
if count + cost > limit then
  return {0, math.max(0, limit - count), now + window_ms}
end
-- Use UUID-like member to avoid duplicates: combine timestamp with a unique nano suffix
for i = 1, cost do
  local member = now .. ':' .. math.random(1, 2147483647) .. ':' .. i
  redis.call('ZADD', key, now, member)
end
redis.call('PEXPIRE', key, window_ms)
return {1, limit - count - cost, now + window_ms}
