-- leaking_bucket.lua
-- Leaking bucket rate limiter: bucket drains at a fixed rate regardless of input.
-- Requests are rejected when the bucket is full (no more capacity to absorb them).
--
-- KEYS[1] = level_key     : current water level (accumulated requests)
-- KEYS[2] = last_check_key: timestamp of last check (ms) for drain calculation
-- ARGV[1] = bucket_size   : max capacity of the bucket (requests)
-- ARGV[2] = drain_rate    : requests drained per second
-- ARGV[3] = cost          : units this request consumes
-- ARGV[4] = now           : current timestamp in ms
--
-- Returns: {allowed, remaining_capacity, retry_after_ms}
--   allowed=1/0, remaining_capacity=floor space left after this request, retry_after_ms=0 if allowed

local level_key      = KEYS[1]
local last_check_key = KEYS[2]
local bucket_size    = tonumber(ARGV[1])
local drain_rate     = tonumber(ARGV[2])
local cost           = tonumber(ARGV[3])
local now            = tonumber(ARGV[4])

local last_check    = tonumber(redis.call('GET', last_check_key) or now)
local current_level = tonumber(redis.call('GET', level_key) or 0)

local elapsed_ms = math.max(0, now - last_check)
local drained    = (elapsed_ms / 1000.0) * drain_rate
local new_level  = math.max(0, current_level - drained)

redis.call('SET', last_check_key, now, 'PX', 3600000)

if new_level + cost > bucket_size then
  redis.call('SET', level_key, new_level, 'PX', 3600000)
  local wait_ms = math.ceil((new_level + cost - bucket_size) / drain_rate * 1000)
  return {0, math.max(0, math.floor(bucket_size - new_level)), now + wait_ms}
end

redis.call('SET', level_key, new_level + cost, 'PX', 3600000)
return {1, math.floor(bucket_size - new_level - cost), 0}
