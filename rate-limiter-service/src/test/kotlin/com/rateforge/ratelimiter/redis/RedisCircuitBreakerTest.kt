package com.rateforge.ratelimiter.redis

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class RedisCircuitBreakerTest {

    // threshold=3, successThreshold=2, openTimeout=50ms (fast for tests)
    private lateinit var cb: RedisCircuitBreaker

    @BeforeEach
    fun setup() {
        cb = RedisCircuitBreaker(failureThreshold = 3, successThreshold = 2, openTimeoutMs = 50L)
    }

    @Test
    fun `starts in CLOSED state`() {
        assertEquals(RedisCircuitBreaker.State.CLOSED, cb.currentState())
    }

    @Test
    fun `trips to OPEN after threshold failures`() {
        repeat(3) {
            cb.execute(operation = { throw RuntimeException("Redis down") }, fallback = { "fallback" })
        }
        assertEquals(RedisCircuitBreaker.State.OPEN, cb.currentState())
    }

    @Test
    fun `calls fallback when OPEN`() {
        repeat(3) { cb.execute(operation = { throw RuntimeException() }, fallback = { }) }
        var fallbackCalled = false
        cb.execute(operation = { throw RuntimeException() }, fallback = { fallbackCalled = true })
        assertTrue(fallbackCalled)
    }

    @Test
    fun `transitions to HALF_OPEN after timeout`() {
        repeat(3) { cb.execute(operation = { throw RuntimeException() }, fallback = { }) }
        Thread.sleep(60)  // wait for openTimeout=50ms
        cb.execute(operation = { "ok" }, fallback = { "fallback" })
        // After 1 success in HALF_OPEN, still HALF_OPEN (needs 2)
        assertEquals(RedisCircuitBreaker.State.HALF_OPEN, cb.currentState())
    }

    @Test
    fun `closes after successThreshold successes in HALF_OPEN`() {
        repeat(3) { cb.execute(operation = { throw RuntimeException() }, fallback = { }) }
        Thread.sleep(60)
        repeat(2) { cb.execute(operation = { "ok" }, fallback = { }) }
        assertEquals(RedisCircuitBreaker.State.CLOSED, cb.currentState())
    }

    @Test
    fun `re-opens from HALF_OPEN on failure`() {
        repeat(3) { cb.execute(operation = { throw RuntimeException() }, fallback = { }) }
        Thread.sleep(60)
        cb.execute(operation = { throw RuntimeException("still down") }, fallback = { })
        assertEquals(RedisCircuitBreaker.State.OPEN, cb.currentState())
    }

    @Test
    fun `resets failure count after a successful call in CLOSED`() {
        repeat(2) { cb.execute(operation = { throw RuntimeException() }, fallback = { }) }
        cb.execute(operation = { "ok" }, fallback = { })  // success resets counter
        cb.execute(operation = { throw RuntimeException() }, fallback = { })
        // Only 1 failure after reset — should still be CLOSED
        assertEquals(RedisCircuitBreaker.State.CLOSED, cb.currentState())
    }
}
