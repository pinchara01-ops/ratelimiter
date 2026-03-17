package com.rateforge.ratelimiter.algorithm

import com.rateforge.ratelimiter.policy.Policy
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.RedisScript

class FixedWindowAlgorithmTest {

    private val redis = mockk<StringRedisTemplate>()
    private val algorithm = FixedWindowAlgorithm(redis)

    private val policy = Policy(
        id = "test-policy", name = "Test", algorithm = AlgorithmType.FIXED_WINDOW,
        limit = 10L, windowSeconds = 60L,
    )

    @Suppress("UNCHECKED_CAST")
    private fun mockScript(vararg result: Long) {
        every {
            redis.execute(any<RedisScript<List<Long>>>(), any(), *anyVararg())
        } returns result.toList()
    }

    @Test
    fun `check returns allowed when under limit`() {
        mockScript(1L, 9L, 60_000L)
        val result = algorithm.check("key:1", policy)
        assertTrue(result.allowed)
        assertEquals(9L, result.remaining)
        assertEquals(AlgorithmType.FIXED_WINDOW, result.algorithm)
    }

    @Test
    fun `check returns denied when at limit`() {
        mockScript(0L, 0L, 30_000L)
        val result = algorithm.check("key:1", policy)
        assertFalse(result.allowed)
        assertEquals(0L, result.remaining)
        assertEquals(30_000L, result.resetMs)
    }

    @Test
    fun `status returns current count and remaining`() {
        mockScript(7L, 45_000L)
        val status = algorithm.status("key:1", policy)
        assertEquals(7L, status.currentCount)
        assertEquals(3L, status.remaining)
        assertEquals(0.7, status.fillRatio, 0.001)
    }

    @Test
    fun `status returns zero remaining when over limit`() {
        mockScript(15L, 5_000L)
        val status = algorithm.status("key:1", policy)
        assertEquals(15L, status.currentCount)
        assertEquals(0L, status.remaining)   // coerced to 0, not negative
    }
}
