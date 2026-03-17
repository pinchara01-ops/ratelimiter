local tokens_key = KEYS[1]
local last_refill_key = KEYS[2]
local bucket_size = tonumber(ARGV[1])
local refill_rate = tonumber(ARGV[2])
local now = tonumber(ARGV[3])

local last_refill = tonumber(redis.call('GET', last_refill_key) or now)
local current_tokens = tonumber(redis.call('GET', tokens_key) or bucket_size)
local elapsed_ms = math.max(0, now - last_refill)
local new_tokens = math.min(bucket_size, current_tokens + (elapsed_ms / 1000.0) * refill_rate)

return math.floor(new_tokens)
