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

class FixedWindowExecutorTest {

    private lateinit var redisTemplate: RedisTemplate<String, String>
    private lateinit var luaScriptLoader: LuaScriptLoader
    private lateinit var executor: FixedWindowExecutor

    private var executeResults: MutableList<List<Long>> = mutableListOf()
    private var executeCallCount = 0

    @BeforeEach
    fun setUp() {
        redisTemplate = mockk()
        luaScriptLoader = mockk()
        val script = mockk<DefaultRedisScript<List<*>>>()
        every { luaScriptLoader.fixedWindowScript } returns script
        
        executeResults = mutableListOf()
        executeCallCount = 0
        
        executor = FixedWindowExecutor(redisTemplate, luaScriptLoader)
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
    
    private fun mockExecuteWithKeyMatch(keyPattern: String, result: List<Long>) {
        every { 
            redisTemplate.execute(any<RedisScript<List<*>>>(), match<List<String>> { it[0].contains(keyPattern) }, *anyVararg())
        } returns result
    }

    @Test
    fun `happy path - request allowed when under limit`() {
        val keysSlot = slot<List<String>>()
        every {
            redisTemplate.execute(any<RedisScript<List<*>>>(), capture(keysSlot), *anyVararg())
        } returns listOf(1L, 9L, System.currentTimeMillis() + 60000L)

        val result = executor.checkLimit("client1", "/api/test", limit = 10L, windowMs = 60000L)

        assertThat(result.allowed).isTrue()
        assertThat(result.remaining).isEqualTo(9L)
        assertThat(result.resetAtMs).isGreaterThan(System.currentTimeMillis())
    }

    @Test
    fun `over limit - request denied`() {
        mockExecuteReturns(listOf(0L, 0L, System.currentTimeMillis() + 30000L))

        val result = executor.checkLimit("client1", "/api/test", limit = 10L, windowMs = 60000L)

        assertThat(result.allowed).isFalse()
        assertThat(result.remaining).isEqualTo(0L)
        assertThat(result.resetAtMs).isGreaterThan(System.currentTimeMillis())
    }

    @Test
    fun `cost greater than 1 - remaining decreases by cost`() {
        mockExecuteReturns(listOf(1L, 5L, System.currentTimeMillis() + 60000L))

        val result = executor.checkLimit("client1", "/api/test", limit = 10L, windowMs = 60000L, cost = 5L)

        assertThat(result.allowed).isTrue()
        assertThat(result.remaining).isEqualTo(5L)
    }

    @Test
    fun `window expiry - new window starts fresh`() {
        mockExecuteReturnsMany(listOf(
            listOf(0L, 0L, System.currentTimeMillis() + 1000L),
            listOf(1L, 9L, System.currentTimeMillis() + 60000L)
        ))

        val result1 = executor.checkLimit("client1", "/api/test", limit = 10L, windowMs = 60000L)
        assertThat(result1.allowed).isFalse()

        // After window expiry - new window
        val result2 = executor.checkLimit("client1", "/api/test", limit = 10L, windowMs = 60000L)
        assertThat(result2.allowed).isTrue()
        assertThat(result2.remaining).isEqualTo(9L)
    }

    @Test
    fun `exact limit - last request allowed, next denied`() {
        mockExecuteReturnsMany(listOf(
            listOf(1L, 0L, System.currentTimeMillis() + 60000L), // exactly at limit
            listOf(0L, 0L, System.currentTimeMillis() + 60000L)  // over limit
        ))

        val result1 = executor.checkLimit("client1", "/api/test", limit = 10L, windowMs = 60000L)
        assertThat(result1.allowed).isTrue()
        assertThat(result1.remaining).isEqualTo(0L)

        val result2 = executor.checkLimit("client1", "/api/test", limit = 10L, windowMs = 60000L)
        assertThat(result2.allowed).isFalse()
    }

    @Test
    fun `different clients have independent windows`() {
        mockExecuteWithKeyMatch("client1", listOf(0L, 0L, System.currentTimeMillis() + 60000L))
        mockExecuteWithKeyMatch("client2", listOf(1L, 9L, System.currentTimeMillis() + 60000L))

        val result1 = executor.checkLimit("client1", "/api/test", limit = 10L, windowMs = 60000L)
        val result2 = executor.checkLimit("client2", "/api/test", limit = 10L, windowMs = 60000L)

        assertThat(result1.allowed).isFalse()
        assertThat(result2.allowed).isTrue()
    }
}
