package com.rateforge.ratelimiter.algorithm

import com.rateforge.ratelimiter.policy.Policy
import org.springframework.core.ParameterizedTypeReference
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Component

/**
 * Token-bucket rate limiter (RAT-8).
 *
 * Stores {tokens, last_refill_ms} in a Redis Hash. The Lua script atomically:
 *   1. Reads current token count and last refill timestamp
 *   2. Computes tokens earned since last refill at [refillRate] tokens/sec
 *   3. Caps tokens at [capacity]
 *   4. Consumes 1 token if available; denies otherwise
 *
 * Advantages over fixed/sliding window: allows short bursts while enforcing
 * a steady-state rate over time.
 *
 * Redis type: Hash  {tokens: double, last_refill_ms: long}
 */
@Component
class TokenBucketAlgorithm(
    private val redis: StringRedisTemplate,
) : RateLimitAlgorithm {

    override val algorithmType = AlgorithmType.TOKEN_BUCKET

    private val checkScript = DefaultRedisScript<List<Long>>().apply {
        setScriptText(CHECK_LUA)
        setResultType(object : ParameterizedTypeReference<List<Long>>() {})
    }

    private val statusScript = DefaultRedisScript<List<Long>>().apply {
        setScriptText(STATUS_LUA)
        setResultType(object : ParameterizedTypeReference<List<Long>>() {})
    }

    override fun check(redisKey: String, policy: Policy): CheckResult {
        val nowMs       = System.currentTimeMillis()
        val capacity    = policy.limit
        val refillRate  = policy.refillRate.coerceAtLeast(1.0)
        // TTL = time to fully refill from 0 × 2 (safety margin)
        val ttlMs       = (capacity / refillRate * 2_000).toLong()

        val result = redis.execute(
            checkScript,
            listOf(redisKey),
            capacity.toString(),
            refillRate.toString(),
            nowMs.toString(),
            ttlMs.toString(),
        )!!
        val waitMs = if (result[0] == 0L) (1_000.0 / refillRate).toLong() else 0L
        return CheckResult(
            allowed   = result[0] == 1L,
            remaining = result[1],
            resetMs   = waitMs,
            algorithm = algorithmType,
        )
    }

    override fun status(redisKey: String, policy: Policy): StatusResult {
        val nowMs = System.currentTimeMillis()
        val result = redis.execute(
            statusScript,
            listOf(redisKey),
            policy.limit.toString(),
            policy.refillRate.toString(),
            nowMs.toString(),
        )!!
        val tokens   = result[0]
        val capacity = policy.limit
        return StatusResult(
            currentCount = capacity - tokens,
            remaining    = tokens,
            fillRatio    = 1.0 - (tokens.toDouble() / capacity.coerceAtLeast(1)),
            resetMs      = (1_000.0 / policy.refillRate.coerceAtLeast(1.0)).toLong(),
        )
    }

    companion object {
        /** RAT-6: Atomic refill + consume in one round-trip */
        val CHECK_LUA = """
            local key         = KEYS[1]
            local capacity    = tonumber(ARGV[1])
            local refill_rate = tonumber(ARGV[2])
            local now_ms      = tonumber(ARGV[3])
            local ttl_ms      = tonumber(ARGV[4])
            local data        = redis.call('HMGET', key, 'tokens', 'last_refill_ms')
            local tokens      = tonumber(data[1]) or capacity
            local last_refill = tonumber(data[2]) or now_ms
            local elapsed_s   = (now_ms - last_refill) / 1000.0
            local new_tokens  = math.min(capacity, tokens + elapsed_s * refill_rate)
            if new_tokens >= 1 then
                new_tokens = new_tokens - 1
                redis.call('HMSET', key, 'tokens', new_tokens, 'last_refill_ms', now_ms)
                redis.call('PEXPIRE', key, ttl_ms)
                return {1, math.floor(new_tokens)}
            else
                redis.call('HMSET', key, 'tokens', new_tokens, 'last_refill_ms', now_ms)
                redis.call('PEXPIRE', key, ttl_ms)
                return {0, 0}
            end
        """.trimIndent()

        val STATUS_LUA = """
            local key         = KEYS[1]
            local capacity    = tonumber(ARGV[1])
            local refill_rate = tonumber(ARGV[2])
            local now_ms      = tonumber(ARGV[3])
            local data        = redis.call('HMGET', key, 'tokens', 'last_refill_ms')
            local tokens      = tonumber(data[1]) or capacity
            local last_refill = tonumber(data[2]) or now_ms
            local elapsed_s   = (now_ms - last_refill) / 1000.0
            local cur_tokens  = math.min(capacity, tokens + elapsed_s * refill_rate)
            return {math.floor(cur_tokens)}
        """.trimIndent()
    }
}
