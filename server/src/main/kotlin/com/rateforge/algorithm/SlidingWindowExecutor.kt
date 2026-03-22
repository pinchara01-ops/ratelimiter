package com.rateforge.algorithm

import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.RedisSystemException
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class SlidingWindowExecutor(
    private val redisTemplate: RedisTemplate<String, String>,
    private val luaScriptLoader: LuaScriptLoader
) {
    private val log = LoggerFactory.getLogger(SlidingWindowExecutor::class.java)

    /**
     * Check sliding window rate limit.
     *
     * Key uses hash tag {clientId:method:endpoint} to ensure cluster slot pinning.
     *
     * @param clientId client identifier
     * @param endpoint the API endpoint
     * @param method the HTTP method (GET, POST, etc.)
     * @param limit max requests within the sliding window
     * @param windowMs window size in milliseconds
     * @param cost number of tokens to consume (default 1)
     * @return RateLimitResult with allowed, remaining, and resetAtMs
     */
    fun checkLimit(
        clientId: String,
        endpoint: String,
        method: String = "*",
        limit: Long,
        windowMs: Long,
        cost: Long = 1L
    ): RateLimitResult {
        val now = Instant.now().toEpochMilli()
        // Use hash tag for cluster slot pinning
        val key = "{rl:$clientId:$method:$endpoint}:events"

        try {
            val result = redisTemplate.execute(
                luaScriptLoader.slidingWindowScript,
                listOf(key),
                limit.toString(),
                windowMs.toString(),
                cost.toString(),
                now.toString()
            ) ?: throw IllegalStateException("Sliding window Lua script returned null")

            return parseResult(result)
        } catch (e: RedisSystemException) {
            log.error("Redis error in sliding window checkLimit for client={} endpoint={} method={}", clientId, endpoint, method, e)
            throw e
        }
    }

    /**
     * Get current remaining count without modifying state (read-only).
     *
     * @param clientId client identifier
     * @param endpoint the API endpoint
     * @param method the HTTP method
     * @param limit max requests within the sliding window
     * @param windowMs window size in milliseconds
     * @return current remaining count
     */
    fun getRemaining(
        clientId: String,
        endpoint: String,
        method: String = "*",
        limit: Long,
        windowMs: Long
    ): Long {
        val now = Instant.now().toEpochMilli()
        val key = "{rl:$clientId:$method:$endpoint}:events"
        val windowStart = now - windowMs

        try {
            // Read-only: use ZRANGEBYSCORE to count events in the current window (exclusive lower bound)
            val count = redisTemplate.opsForZSet()
                .rangeByScore(key, windowStart.toDouble(), now.toDouble())
                ?.size?.toLong() ?: 0L
            return maxOf(0L, limit - count)
        } catch (e: RedisSystemException) {
            log.error("Redis error in sliding window getRemaining for client={} endpoint={} method={}", clientId, endpoint, method, e)
            throw e
        }
    }

    private fun parseResult(result: List<*>): RateLimitResult {
        val allowed = (result[0] as Long) == 1L
        val remaining = result[1] as Long
        val resetAtMs = result[2] as Long
        return RateLimitResult(allowed, remaining, resetAtMs)
    }
}
