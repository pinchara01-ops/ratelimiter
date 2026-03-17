package com.rateforge.hotkey

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.lang.reflect.Field
import java.util.concurrent.atomic.AtomicLong

class LocalPreCounterTest {

    // threshold=5 req/s so we can trigger it easily in tests
    private val counter = LocalPreCounter(batchSize = 10L, hotThreshold = 5L)

    @Test
    fun `key is not hot below threshold`() {
        repeat(3) { counter.recordRequest("cool-key") }
        assertThat(counter.isHotKey("cool-key")).isFalse()
    }

    @Test
    fun `key becomes hot above threshold`() {
        repeat(6) { counter.recordRequest("hot-key") }
        assertThat(counter.isHotKey("hot-key")).isTrue()
    }

    @Test
    fun `unknown key is not hot`() {
        assertThat(counter.isHotKey("never-seen")).isFalse()
    }

    @Test
    fun `tryConsumeLocal returns false when no budget`() {
        repeat(6) { counter.recordRequest("hot-key") }
        // no budget granted yet
        assertThat(counter.tryConsumeLocal("hot-key")).isFalse()
    }

    @Test
    fun `tryConsumeLocal succeeds after budget granted`() {
        repeat(6) { counter.recordRequest("hot-key") }
        counter.grantBudget("hot-key", 3L)

        assertThat(counter.tryConsumeLocal("hot-key")).isTrue()
        assertThat(counter.tryConsumeLocal("hot-key")).isTrue()
        assertThat(counter.tryConsumeLocal("hot-key")).isTrue()
        // 4th consume: budget exhausted
        assertThat(counter.tryConsumeLocal("hot-key")).isFalse()
    }

    @Test
    fun `batchSize is accessible`() {
        assertThat(counter.batchSize).isEqualTo(10L)
    }

    @Test
    fun `grantBudget accumulates across calls`() {
        counter.grantBudget("key", 5L)
        counter.grantBudget("key", 5L)
        // 10 total slots
        repeat(10) { assertThat(counter.tryConsumeLocal("key")).isTrue() }
        assertThat(counter.tryConsumeLocal("key")).isFalse()
    }

    @Test
    fun `window resets after 1 second - simulated via reflection`() {
        repeat(6) { counter.recordRequest("reset-key") }
        assertThat(counter.isHotKey("reset-key")).isTrue()

        // Rewind the windowStartMs so it looks like > 1s has passed
        setWindowStart("reset-key", System.currentTimeMillis() - 2_000L)

        // isHotKey triggers refreshWindow which resets requestsInWindow
        assertThat(counter.isHotKey("reset-key")).isFalse()
    }

    /**
     * Uses reflection to rewind the windowStartMs of the slot for [key],
     * simulating time passage without Thread.sleep.
     */
    private fun setWindowStart(key: String, timestampMs: Long) {
        // Access the private `slots` field
        val slotsField = LocalPreCounter::class.java.getDeclaredField("slots")
        slotsField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val cache = slotsField.get(counter) as com.github.benmanes.caffeine.cache.Cache<String, Any>
        val slot = cache.getIfPresent(key) ?: return

        // Access the `windowStartMs` field inside the Slot data class
        val windowField = slot.javaClass.getDeclaredField("windowStartMs")
        windowField.isAccessible = true
        val windowRef = windowField.get(slot) as AtomicLong
        windowRef.set(timestampMs)
    }
}
