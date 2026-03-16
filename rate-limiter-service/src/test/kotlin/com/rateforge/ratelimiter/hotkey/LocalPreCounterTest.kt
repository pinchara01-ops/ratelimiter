package com.rateforge.ratelimiter.hotkey

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LocalPreCounterTest {

    // threshold=5 req/s so we can trigger it easily in tests
    private val counter = LocalPreCounter(batchSize = 10L, hotThreshold = 5L)

    @Test
    fun `key is not hot below threshold`() {
        repeat(3) { counter.recordRequest("cool-key") }
        assertFalse(counter.isHotKey("cool-key"))
    }

    @Test
    fun `key becomes hot above threshold`() {
        repeat(6) { counter.recordRequest("hot-key") }
        assertTrue(counter.isHotKey("hot-key"))
    }

    @Test
    fun `unknown key is not hot`() {
        assertFalse(counter.isHotKey("never-seen"))
    }

    @Test
    fun `tryConsumeLocal returns false when no budget`() {
        repeat(6) { counter.recordRequest("hot-key") }
        // no budget granted yet
        assertFalse(counter.tryConsumeLocal("hot-key"))
    }

    @Test
    fun `tryConsumeLocal succeeds after budget granted`() {
        repeat(6) { counter.recordRequest("hot-key") }
        counter.grantBudget("hot-key", 3L)

        assertTrue(counter.tryConsumeLocal("hot-key"))
        assertTrue(counter.tryConsumeLocal("hot-key"))
        assertTrue(counter.tryConsumeLocal("hot-key"))
        // 4th consume: budget exhausted
        assertFalse(counter.tryConsumeLocal("hot-key"))
    }

    @Test
    fun `batchSize is accessible`() {
        assertEquals(10L, counter.batchSize)
    }

    @Test
    fun `grantBudget accumulates across calls`() {
        counter.grantBudget("key", 5L)
        counter.grantBudget("key", 5L)
        // 10 total slots
        repeat(10) { assertTrue(counter.tryConsumeLocal("key")) }
        assertFalse(counter.tryConsumeLocal("key"))
    }
}
