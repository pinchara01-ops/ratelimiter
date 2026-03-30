package com.rateforge.algorithm

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.data.redis.core.script.RedisScript

class TokenBucketExecutorTest {

    private lateinit var redisTemplate: RedisTemplate<String, String>
    private lateinit var luaScriptLoader: LuaScriptLoader
    private lateinit var executor: TokenBucketExecutor

    private var executeResults: MutableList<List<Long>> = mutableListOf()
    private var executeCallCount = 0

    @BeforeEach
    fun setUp() {
        redisTemplate = mockk()
        luaScriptLoader = mockk()
        val script = mockk<DefaultRedisScript<List<*>>>()
        every { luaScriptLoader.tokenBucketScript } returns script
        
        executeResults = mutableListOf()
        executeCallCount = 0
        
        executor = TokenBucketExecutor(redisTemplate, luaScriptLoader)
    }
    
    private fun mockExecuteReturns(result: List<Long>) {
        every { 
            redisTemplate.execute(any<RedisScript<List<*>>>(), any<List<String>>(), *anyVararg())
        } returns result
    }
    
    private fun mockExecuteReturnsMany(results: List<List<Long>>) {
        executeResults = results.toMutableList()
        executeCallCount = 0
        every { 
            redisTemplate.execute(any<RedisScript<List<*>>>(), any<List<String>>(), *anyVararg())
        } answers {
            val result = executeResults.getOrElse(executeCallCount) { executeResults.last() }
            executeCallCount++
            result
        }
    }

    @Test
    fun `bucket has tokens - request allowed`() {
        mockExecuteReturns(listOf(1L, 9L, 0L))

        val result = executor.checkLimit("client1", "/api/test", bucketSize = 10L, refillRate = 1.0)

        assertThat(result.allowed).isTrue()
        assertThat(result.remaining).isEqualTo(9L)
        assertThat(result.resetAtMs).isEqualTo(0L)
    }

    @Test
    fun `burst - full bucket allows many requests rapidly`() {
        mockExecuteReturnsMany((10 downTo 1).map { remaining ->
            listOf(1L, remaining.toLong() - 1L, 0L)
        })

        // All 10 requests in the burst should be allowed
        for (i in 1..10) {
            val result = executor.checkLimit("client1", "/api/test", bucketSize = 10L, refillRate = 1.0)
            assertThat(result.allowed).isTrue()
        }
    }

    @Test
    fun `empty bucket - request denied`() {
        val resetAtMs = System.currentTimeMillis() + 1000L
        mockExecuteReturns(listOf(0L, 0L, resetAtMs))

        val result = executor.checkLimit("client1", "/api/test", bucketSize = 10L, refillRate = 1.0)

        assertThat(result.allowed).isFalse()
        assertThat(result.remaining).isEqualTo(0L)
        assertThat(result.resetAtMs).isEqualTo(resetAtMs)
    }

    @Test
    fun `refill after elapsed time - tokens replenished`() {
        val now = System.currentTimeMillis()

        mockExecuteReturnsMany(listOf(
            listOf(0L, 0L, now + 5000L),   // bucket empty
            listOf(1L, 4L, 0L)              // after refill (5 tokens added)
        ))

        val result1 = executor.checkLimit("client1", "/api/test", bucketSize = 10L, refillRate = 1.0)
        assertThat(result1.allowed).isFalse()

        // After 5 seconds, 5 tokens refilled
        val result2 = executor.checkLimit("client1", "/api/test", bucketSize = 10L, refillRate = 1.0)
        assertThat(result2.allowed).isTrue()
        assertThat(result2.remaining).isEqualTo(4L)
    }

    @Test
    fun `token bucket keys use hash tag for cluster slot pinning`() {
        val keysSlot = slot<List<String>>()
        every {
            redisTemplate.execute(any<RedisScript<List<*>>>(), capture(keysSlot), *anyVararg())
        } returns listOf(1L, 9L, 0L)

        executor.checkLimit("myClient", "/api/endpoint", bucketSize = 10L, refillRate = 2.0)

        val keys = keysSlot.captured
        assertThat(keys).hasSize(2)
        // Both keys should share the same hash tag for cluster slot pinning
        assertThat(keys[0]).contains("{rl:myClient:*:/api/endpoint}")
        assertThat(keys[0]).endsWith(":tokens")
        assertThat(keys[1]).contains("{rl:myClient:*:/api/endpoint}")
        assertThat(keys[1]).endsWith(":last_refill")
    }

    @Test
    fun `cost greater than 1 consumes multiple tokens`() {
        mockExecuteReturns(listOf(1L, 5L, 0L))

        val result = executor.checkLimit("client1", "/api/test", bucketSize = 10L, refillRate = 1.0, cost = 5L)

        assertThat(result.allowed).isTrue()
        assertThat(result.remaining).isEqualTo(5L)
    }

    @Test
    fun `insufficient tokens for cost - request denied`() {
        mockExecuteReturns(listOf(0L, 3L, System.currentTimeMillis() + 2000L))

        // Only 3 tokens available, cost is 5
        val result = executor.checkLimit("client1", "/api/test", bucketSize = 10L, refillRate = 1.0, cost = 5L)

        assertThat(result.allowed).isFalse()
        assertThat(result.remaining).isEqualTo(3L)
    }
}
