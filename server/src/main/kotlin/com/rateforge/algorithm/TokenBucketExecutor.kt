package com.rateforge.algorithm

import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.core.RedisSystemException
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class TokenBucketExecutor(
    private val redisTemplate: RedisTemplate<String, String>,
    private val luaScriptLoader: LuaScriptLoader
) {
    private val log = LoggerFactory.getLogger(TokenBucketExecutor::class.java)

    /**
     * Check token bucket rate limit.
     *
     * Both keys share the same hash tag {clientId:method:endpoint} for cluster slot pinning.
     *
     * @param clientId client identifier
     * @param endpoint the API endpoint
     * @param method the HTTP method (GET, POST, etc.)
     * @param bucketSize maximum token capacity
     * @param refillRate tokens per second
     * @param cost number of tokens to consume (default 1)
     * @return RateLimitResult with allowed, remaining, and resetAtMs
     */
    fun checkLimit(
        clientId: String,
        endpoint: String,
        method: String = "*",
        bucketSize: Long,
        refillRate: Double,
        cost: Long = 1L
    ): RateLimitResult {
        val now = Instant.now().toEpochMilli()
        // Both keys share the same hash tag for cluster slot pinning
        val tokensKey = "{rl:$clientId:$method:$endpoint}:tokens"
        val lastRefillKey = "{rl:$clientId:$method:$endpoint}:last_refill"

        try {
            val result = redisTemplate.execute(
                luaScriptLoader.tokenBucketScript,
                listOf(tokensKey, lastRefillKey),
                bucketSize.toString(),
                refillRate.toString(),
                cost.toString(),
                now.toString()
            ) ?: throw IllegalStateException("Token bucket Lua script returned null")

            return parseResult(result)
        } catch (e: RedisSystemException) {
            log.error("Redis error in token bucket checkLimit for client={} endpoint={} method={}", clientId, endpoint, method, e)
            throw e
        }
    }

    /**
     * Get current remaining token count without consuming tokens.
     *
     * @param clientId client identifier
     * @param endpoint the API endpoint
     * @param method the HTTP method
     * @param bucketSize maximum token capacity
     * @param refillRate tokens per second
     * @return current remaining token count (with refill applied)
     */
    fun getRemaining(
        clientId: String,
        endpoint: String,
        method: String = "*",
        bucketSize: Long,
        refillRate: Double
    ): Long {
        val now = Instant.now().toEpochMilli()
        val tokensKey = "{rl:$clientId:$method:$endpoint}:tokens"
        val lastRefillKey = "{rl:$clientId:$method:$endpoint}:last_refill"

        try {
            val remaining = redisTemplate.execute(
                luaScriptLoader.tokenBucketStatusScript,
                listOf(tokensKey, lastRefillKey),
                bucketSize.toString(),
                refillRate.toString(),
                now.toString()
            ) ?: bucketSize  // default to full bucket if script returns null
            return remaining
        } catch (e: RedisSystemException) {
            log.error("Redis error in token bucket getRemaining for client={} endpoint={} method={}", clientId, endpoint, method, e)
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
