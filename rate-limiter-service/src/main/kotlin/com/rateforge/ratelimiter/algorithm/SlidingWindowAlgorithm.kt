package com.rateforge.ratelimiter.algorithm

import com.rateforge.ratelimiter.policy.Policy
import org.springframework.core.ParameterizedTypeReference
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Sliding-window rate limiter (RAT-7).
 *
 * Uses a Redis Sorted Set (ZSET) where each entry's score is its arrival
 * timestamp in milliseconds. The Lua script atomically:
 *   1. Removes entries older than [now - windowMs]
 *   2. Counts remaining entries
 *   3. Adds the new entry if count < limit
 *
 * More accurate than fixed-window under bursty traffic but uses more Redis memory.
 *
 * Redis type: Sorted Set  (score = timestamp ms, member = unique request ID)
 */
@Component
class SlidingWindowAlgorithm(
    private val redis: StringRedisTemplate,
) : RateLimitAlgorithm {

    override val algorithmType = AlgorithmType.SLIDING_WINDOW

    private val checkScript = DefaultRedisScript<List<Long>>().apply {
        setScriptText(CHECK_LUA)
        setResultType(object : ParameterizedTypeReference<List<Long>>() {})
    }

    private val statusScript = DefaultRedisScript<List<Long>>().apply {
        setScriptText(STATUS_LUA)
        setResultType(object : ParameterizedTypeReference<List<Long>>() {})
    }

    override fun check(redisKey: String, policy: Policy): CheckResult {
        val nowMs     = System.currentTimeMillis()
        val windowMs  = policy.windowSeconds * 1_000L
        val entryId   = UUID.randomUUID().toString()

        val result = redis.execute(
            checkScript,
            listOf(redisKey),
            policy.limit.toString(),
            nowMs.toString(),
            windowMs.toString(),
            entryId,
        )!!
        return CheckResult(
            allowed   = result[0] == 1L,
            remaining = result[1],
            resetMs   = result[2],
            algorithm = algorithmType,
        )
    }

    override fun status(redisKey: String, policy: Policy): StatusResult {
        val nowMs    = System.currentTimeMillis()
        val windowMs = policy.windowSeconds * 1_000L

        val result = redis.execute(
            statusScript,
            listOf(redisKey),
            policy.limit.toString(),
            nowMs.toString(),
            windowMs.toString(),
        )!!
        val current = result[0]
        return StatusResult(
            currentCount = current,
            remaining    = maxOf(0L, policy.limit - current),
            fillRatio    = current.toDouble() / policy.limit.coerceAtLeast(1),
            resetMs      = windowMs,  // sliding: window always rolls forward
        )
    }

    companion object {
        /** RAT-6: Atomic prune + count + conditional add in one round-trip */
        val CHECK_LUA = """
            local key      = KEYS[1]
            local limit    = tonumber(ARGV[1])
            local now_ms   = tonumber(ARGV[2])
            local window   = tonumber(ARGV[3])
            local entry_id = ARGV[4]
            local cutoff   = now_ms - window
            redis.call('ZREMRANGEBYSCORE', key, 0, cutoff)
            local count = tonumber(redis.call('ZCARD', key))
            if count < limit then
                redis.call('ZADD', key, now_ms, entry_id)
                redis.call('PEXPIRE', key, window)
                return {1, limit - count - 1, window}
            else
                return {0, 0, window}
            end
        """.trimIndent()

        val STATUS_LUA = """
            local key    = KEYS[1]
            local limit  = tonumber(ARGV[1])
            local now_ms = tonumber(ARGV[2])
            local window = tonumber(ARGV[3])
            redis.call('ZREMRANGEBYSCORE', key, 0, now_ms - window)
            local count  = tonumber(redis.call('ZCARD', key))
            return {count, window}
        """.trimIndent()
    }
}
