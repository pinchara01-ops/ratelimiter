package com.rateforge.algorithm

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript

class SlidingWindowExecutorTest {

    private lateinit var redisTemplate: RedisTemplate<String, String>
    private lateinit var luaScriptLoader: LuaScriptLoader
    private lateinit var executor: SlidingWindowExecutor

    @BeforeEach
    fun setUp() {
        redisTemplate = mockk()
        luaScriptLoader = mockk()
        val script = mockk<DefaultRedisScript<List<*>>>()
        every { luaScriptLoader.slidingWindowScript } returns script
        executor = SlidingWindowExecutor(redisTemplate, luaScriptLoader)
    }

    @Test
    fun `happy path - request allowed under limit`() {
        every {
            redisTemplate.execute(any<DefaultRedisScript<List<*>>>(), any<List<String>>(), any<String>(), any<String>(), any<String>(), any<String>())
        } returns listOf(1L, 9L, System.currentTimeMillis() + 60000L)

        val result = executor.checkLimit("client1", "/api/test", limit = 10L, windowMs = 60000L)

        assertThat(result.allowed).isTrue()
        assertThat(result.remaining).isEqualTo(9L)
    }

    @Test
    fun `over limit - request denied`() {
        every {
            redisTemplate.execute(any<DefaultRedisScript<List<*>>>(), any<List<String>>(), any<String>(), any<String>(), any<String>(), any<String>())
        } returns listOf(0L, 0L, System.currentTimeMillis() + 60000L)

        val result = executor.checkLimit("client1", "/api/test", limit = 10L, windowMs = 60000L)

        assertThat(result.allowed).isFalse()
        assertThat(result.remaining).isEqualTo(0L)
    }

    @Test
    fun `sliding boundary - request at t=0 and t=window+1 both allowed`() {
        val windowMs = 60000L
        val now = System.currentTimeMillis()

        // Simulate: at t=0, request allowed (first request in window)
        every {
            redisTemplate.execute(any<DefaultRedisScript<List<*>>>(), any<List<String>>(), any<String>(), any<String>(), any<String>(), any<String>())
        } returnsMany listOf(
            listOf(1L, 9L, now + windowMs),           // t=0: allowed
            listOf(0L, 0L, now + windowMs),            // t=window-1: denied (full)
            listOf(1L, 9L, now + windowMs + windowMs)  // t=window+1: allowed (old events expired)
        )

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
        every {
            redisTemplate.execute(any<DefaultRedisScript<List<*>>>(), any<List<String>>(), any<String>(), any<String>(), any<String>(), any<String>())
        } returns listOf(1L, 5L, System.currentTimeMillis() + 60000L)

        val result = executor.checkLimit("client1", "/api/test", limit = 10L, windowMs = 60000L, cost = 5L)

        assertThat(result.allowed).isTrue()
        assertThat(result.remaining).isEqualTo(5L)
    }

    @Test
    fun `key uses hash tag for cluster slot pinning`() {
        val keysSlot = slot<List<String>>()
        every {
            redisTemplate.execute(any<DefaultRedisScript<List<*>>>(), capture(keysSlot), any<String>(), any<String>(), any<String>(), any<String>())
        } returns listOf(1L, 9L, System.currentTimeMillis() + 60000L)

        executor.checkLimit("myClient", "/api/endpoint", limit = 10L, windowMs = 60000L)

        val key = keysSlot.captured[0]
        // Key should contain hash tag for cluster slot pinning
        assertThat(key).contains("{rl:myClient:*:/api/endpoint}")
        assertThat(key).endsWith(":events")
    }

    @Test
    fun `remaining never goes below zero`() {
        every {
            redisTemplate.execute(any<DefaultRedisScript<List<*>>>(), any<List<String>>(), any<String>(), any<String>(), any<String>(), any<String>())
        } returns listOf(0L, 0L, System.currentTimeMillis() + 60000L)

        val result = executor.checkLimit("client1", "/api/test", limit = 5L, windowMs = 60000L)

        assertThat(result.remaining).isGreaterThanOrEqualTo(0L)
    }
}
