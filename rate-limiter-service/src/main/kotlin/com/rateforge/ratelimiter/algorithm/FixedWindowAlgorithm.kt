package com.rateforge.ratelimiter.algorithm

import com.rateforge.ratelimiter.policy.Policy
import org.springframework.core.ParameterizedTypeReference
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Component

/**
 * Fixed-window rate limiter (RAT-5, RAT-6).
 *
 * Uses a single Redis INCR + conditional EXPIRE. The Lua script ensures
 * atomicity: no race between the increment and the expiry set.
 *
 * Redis key: caller-supplied (e.g. "rl:fixed:{policyId}:{clientKey}")
 * Redis type: String (integer counter)
 */
@Component
class FixedWindowAlgorithm(
    private val redis: StringRedisTemplate,
) : RateLimitAlgorithm {

    override val algorithmType = AlgorithmType.FIXED_WINDOW

    // Returns [allowed(0/1), remaining, resetMs]
    private val checkScript = DefaultRedisScript<List<Long>>().apply {
        setScriptText(CHECK_LUA)
        setResultType(object : ParameterizedTypeReference<List<Long>>() {})
    }

    // Returns [currentCount, resetMs]
    private val statusScript = DefaultRedisScript<List<Long>>().apply {
        setScriptText(STATUS_LUA)
        setResultType(object : ParameterizedTypeReference<List<Long>>() {})
    }

    override fun check(redisKey: String, policy: Policy): CheckResult {
        val result = redis.execute(
            checkScript,
            listOf(redisKey),
            policy.limit.toString(),
            policy.windowSeconds.toString(),
        )!!
        return CheckResult(
            allowed    = result[0] == 1L,
            remaining  = result[1],
            resetMs    = result[2],
            algorithm  = algorithmType,
        )
    }

    override fun status(redisKey: String, policy: Policy): StatusResult {
        val result = redis.execute(statusScript, listOf(redisKey), policy.limit.toString())!!
        val current = result[0]
        return StatusResult(
            currentCount = current,
            remaining    = maxOf(0L, policy.limit - current),
            fillRatio    = current.toDouble() / policy.limit.coerceAtLeast(1),
            resetMs      = result[1],
        )
    }

    companion object {
        /** RAT-6: Atomic INCR + EXPIRE in one round-trip */
        val CHECK_LUA = """
            local key    = KEYS[1]
            local limit  = tonumber(ARGV[1])
            local window = tonumber(ARGV[2])
            local n = tonumber(redis.call('INCR', key))
            if n == 1 then
                redis.call('EXPIRE', key, window)
            end
            local ttl = tonumber(redis.call('TTL', key))
            if n <= limit then
                return {1, limit - n, ttl * 1000}
            else
                return {0, 0, ttl * 1000}
            end
        """.trimIndent()

        val STATUS_LUA = """
            local key   = KEYS[1]
            local limit = tonumber(ARGV[1])
            local n     = tonumber(redis.call('GET', key) or '0')
            local ttl   = tonumber(redis.call('TTL', key))
            return {n, math.max(0, ttl * 1000)}
        """.trimIndent()
    }
}
