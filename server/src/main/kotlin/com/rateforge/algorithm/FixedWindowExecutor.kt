package com.rateforge.algorithm

import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.RedisSystemException
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class FixedWindowExecutor(
    private val redisTemplate: RedisTemplate<String, String>,
    private val luaScriptLoader: LuaScriptLoader
) {
    private val log = LoggerFactory.getLogger(FixedWindowExecutor::class.java)

    /**
     * Check fixed window rate limit.
     *
     * @param clientId client identifier
     * @param endpoint the API endpoint
     * @param method the HTTP method (GET, POST, etc.)
     * @param limit max requests per window
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
        val windowStart = (now / windowMs) * windowMs
        val key = "rl:$clientId:$method:$endpoint:$windowStart"

        try {
            val result = redisTemplate.execute(
                luaScriptLoader.fixedWindowScript,
                listOf(key),
                limit.toString(),
                windowMs.toString(),
                cost.toString(),
                now.toString()
            ) ?: throw IllegalStateException("Fixed window Lua script returned null")

            return parseResult(result)
        } catch (e: RedisSystemException) {
            log.error("Redis error in fixed window checkLimit for client={} endpoint={} method={}", clientId, endpoint, method, e)
            throw e
        }
    }

    /**
     * Get current remaining count without modifying state.
     *
     * @param clientId client identifier
     * @param endpoint the API endpoint
     * @param method the HTTP method
     * @param limit max requests per window
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
        val windowStart = (now / windowMs) * windowMs
        val key = "rl:$clientId:$method:$endpoint:$windowStart"

        try {
            val count = redisTemplate.opsForValue().get(key)?.toLongOrNull() ?: 0L
            return maxOf(0L, limit - count)
        } catch (e: RedisSystemException) {
            log.error("Redis error in fixed window getRemaining for client={} endpoint={} method={}", clientId, endpoint, method, e)
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
