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

class SlidingWindowAlgorithmTest {

    private val redis = mockk<StringRedisTemplate>()
    private val algorithm = SlidingWindowAlgorithm(redis)

    private val policy = Policy(
        id = "sliding-policy", name = "Sliding", algorithm = AlgorithmType.SLIDING_WINDOW,
        limit = 5L, windowSeconds = 10L,
    )

    @Suppress("UNCHECKED_CAST")
    private fun mockScript(vararg result: Long) {
        every {
            redis.execute(any<RedisScript<List<Long>>>(), any(), *anyVararg())
        } returns result.toList()
    }

    @Test
    fun `check allows request when under limit`() {
        mockScript(1L, 4L, 10_000L)
        val result = algorithm.check("key:sliding", policy)
        assertTrue(result.allowed)
        assertEquals(4L, result.remaining)
        assertEquals(AlgorithmType.SLIDING_WINDOW, result.algorithm)
    }

    @Test
    fun `check denies request when at limit`() {
        mockScript(0L, 0L, 10_000L)
        val result = algorithm.check("key:sliding", policy)
        assertFalse(result.allowed)
        assertEquals(0L, result.remaining)
    }

    @Test
    fun `status returns sliding window count`() {
        mockScript(3L, 10_000L)
        val status = algorithm.status("key:sliding", policy)
        assertEquals(3L, status.currentCount)
        assertEquals(2L, status.remaining)
        assertEquals(0.6, status.fillRatio, 0.001)
    }
}
