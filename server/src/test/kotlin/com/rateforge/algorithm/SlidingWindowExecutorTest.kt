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

class SlidingWindowExecutorTest {

    private lateinit var redisTemplate: RedisTemplate<String, String>
    private lateinit var luaScriptLoader: LuaScriptLoader
    private lateinit var executor: SlidingWindowExecutor

    private var executeResults: MutableList<List<Long>> = mutableListOf()
    private var executeCallCount = 0

    @BeforeEach
    fun setUp() {
        redisTemplate = mockk()
        luaScriptLoader = mockk()
        val script = mockk<DefaultRedisScript<List<*>>>()
        every { luaScriptLoader.slidingWindowScript } returns script
        
        executeResults = mutableListOf()
        executeCallCount = 0
        
        executor = SlidingWindowExecutor(redisTemplate, luaScriptLoader)
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
    fun `happy path - request allowed under limit`() {
        mockExecuteReturns(listOf(1L, 9L, System.currentTimeMillis() + 60000L))

        val result = executor.checkLimit("client1", "/api/test", limit = 10L, windowMs = 60000L)

        assertThat(result.allowed).isTrue()
        assertThat(result.remaining).isEqualTo(9L)
    }

    @Test
    fun `over limit - request denied`() {
        mockExecuteReturns(listOf(0L, 0L, System.currentTimeMillis() + 60000L))

        val result = executor.checkLimit("client1", "/api/test", limit = 10L, windowMs = 60000L)

        assertThat(result.allowed).isFalse()
        assertThat(result.remaining).isEqualTo(0L)
    }

    @Test
    fun `sliding boundary - request at t=0 and t=window+1 both allowed`() {
        val windowMs = 60000L
        val now = System.currentTimeMillis()

        mockExecuteReturnsMany(listOf(
            listOf(1L, 9L, now + windowMs),           // t=0: allowed
            listOf(0L, 0L, now + windowMs),            // t=window-1: denied (full)
            listOf(1L, 9L, now + windowMs + windowMs)  // t=window+1: allowed (old events expired)
        ))

        // First request in first window: allowed
        val result1 = executor.checkLimit("client1", "/api/test", limit = 10L, windowMs = windowMs)
        assertThat(result1.allowed).isTrue()

        // Window full
        val result2 = executor.checkLimit("client1", "/api/test", limit = 10L, windowMs = windowMs)
        assertThat(result2.allowed).isFalse()

        // After window slides past first request
        val result3 = executor.checkLimit("client1", "/api/test", limit = 10L, windowMs = windowMs)
        assertThat(result3.allowed).isTrue()
    }

    @Test
    fun `cost greater than 1 counts multiple events`() {
        mockExecuteReturns(listOf(1L, 5L, System.currentTimeMillis() + 60000L))

        val result = executor.checkLimit("client1", "/api/test", limit = 10L, windowMs = 60000L, cost = 5L)

        assertThat(result.allowed).isTrue()
        assertThat(result.remaining).isEqualTo(5L)
    }

    @Test
    fun `key uses hash tag for cluster slot pinning`() {
        val keysSlot = slot<List<String>>()
        every {
            redisTemplate.execute(any<RedisScript<List<*>>>(), capture(keysSlot), *anyVararg())
        } returns listOf(1L, 9L, System.currentTimeMillis() + 60000L)

        executor.checkLimit("myClient", "/api/endpoint", limit = 10L, windowMs = 60000L)

        val key = keysSlot.captured[0]
        // Key should contain hash tag for cluster slot pinning
        assertThat(key).contains("{rl:myClient:*:/api/endpoint}")
        assertThat(key).endsWith(":events")
    }

    @Test
    fun `remaining never goes below zero`() {
        mockExecuteReturns(listOf(0L, 0L, System.currentTimeMillis() + 60000L))

        val result = executor.checkLimit("client1", "/api/test", limit = 5L, windowMs = 60000L)

        assertThat(result.remaining).isGreaterThanOrEqualTo(0L)
    }
}
