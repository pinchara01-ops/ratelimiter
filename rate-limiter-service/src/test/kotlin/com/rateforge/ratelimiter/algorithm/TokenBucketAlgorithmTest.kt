package com.rateforge.ratelimiter.algorithm

import com.rateforge.ratelimiter.policy.Policy
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.RedisScript

class TokenBucketAlgorithmTest {

    private val redis = mockk<StringRedisTemplate>()
    private val algorithm = TokenBucketAlgorithm(redis)

    private val policy = Policy(
        id = "tb-policy", name = "Token Bucket", algorithm = AlgorithmType.TOKEN_BUCKET,
        limit = 100L, windowSeconds = 60L, refillRate = 10.0,
    )

    @Suppress("UNCHECKED_CAST")
    private fun mockScript(vararg result: Long) {
        every {
            redis.execute(any<RedisScript<List<Long>>>(), any(), *anyVararg())
        } returns result.toList()
    }

    @Test
    fun `check consumes token when bucket has tokens`() {
        mockScript(1L, 99L)
        val result = algorithm.check("key:tb", policy)
        assertTrue(result.allowed)
        assertEquals(99L, result.remaining)
        assertEquals(AlgorithmType.TOKEN_BUCKET, result.algorithm)
    }

    @Test
    fun `check denies when bucket is empty`() {
        mockScript(0L, 0L)
        val result = algorithm.check("key:tb", policy)
        assertFalse(result.allowed)
        assertEquals(0L, result.remaining)
        // resetMs = 1000 / refillRate = 100ms
        assertEquals(100L, result.resetMs)
    }

    @Test
    fun `status returns current token count`() {
        mockScript(75L)
        val status = algorithm.status("key:tb", policy)
        assertEquals(75L, status.remaining)
        assertEquals(25L, status.currentCount)    // capacity - tokens
        assertEquals(0.25, status.fillRatio, 0.01) // 1 - 75/100
    }
}
