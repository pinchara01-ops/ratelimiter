local tokens_key = KEYS[1]
local last_refill_key = KEYS[2]
local bucket_size = tonumber(ARGV[1])
local refill_rate = tonumber(ARGV[2])  -- tokens per second
local cost = tonumber(ARGV[3])
local now = tonumber(ARGV[4])

local last_refill = tonumber(redis.call('GET', last_refill_key) or now)
local current_tokens = tonumber(redis.call('GET', tokens_key) or bucket_size)
local elapsed_ms = math.max(0, now - last_refill)
local new_tokens = math.min(bucket_size, current_tokens + (elapsed_ms / 1000.0) * refill_rate)

-- Always persist updated token count and refill timestamp (even on denial)
redis.call('SET', tokens_key, new_tokens, 'PX', 3600000)
redis.call('SET', last_refill_key, now, 'PX', 3600000)

if new_tokens < cost then
  local wait_ms = math.ceil((cost - new_tokens) / refill_rate * 1000)
  return {0, math.floor(new_tokens), now + wait_ms}
end

local remaining = new_tokens - cost
redis.call('SET', tokens_key, remaining, 'PX', 3600000)
return {1, math.floor(remaining), 0}
