package com.rateforge.ratelimiter.hotkey

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.LongAdder

/**
 * Hot-key mitigation via local pre-counting (RAT-19).
 *
 * Problem: A single high-traffic key (e.g. a popular API key or shared IP)
 * generates thousands of Redis calls per second, creating a hot-spot.
 *
 * Solution: Each node pre-acquires a batch of [batchSize] slots from Redis
 * atomically. Subsequent requests within that batch are served entirely
 * from the local counter — no Redis round-trips. When the local budget is
 * exhausted, the node goes back to Redis for another batch.
 *
 * Hot-key detection: A key is "hot" once it exceeds [hotThreshold] req/s
 * within a 1-second sliding window on this node.
 *
 * Flow:
 *   request → recordRequest(key)
 *   if isHotKey(key):
 *     if tryConsumeLocal(key) → ALLOW (no Redis)
 *     else → go to Redis, call grantBudget(key, batchSize) on success
 *   else:
 *     → go to Redis as normal
 */
@Component
class LocalPreCounter(
    val batchSize: Long = 10L,
    private val hotThreshold: Long = 100L,  // req/s on this node
) {
    private val log = LoggerFactory.getLogger(LocalPreCounter::class.java)

    private data class Slot(
        val localBudget:      AtomicLong  = AtomicLong(0L),
        val requestsInWindow: LongAdder   = LongAdder(),
        val windowStartMs:    AtomicLong  = AtomicLong(System.currentTimeMillis()),
    )

    private val slots = ConcurrentHashMap<String, Slot>()

    /** Must be called for every incoming request on this key. */
    fun recordRequest(key: String) {
        val slot = slots.computeIfAbsent(key) { Slot() }
        refreshWindow(slot)
        slot.requestsInWindow.increment()
    }

    /** Returns true if this key's request rate exceeds [hotThreshold] req/s. */
    fun isHotKey(key: String): Boolean {
        val slot = slots[key] ?: return false
        refreshWindow(slot)
        return slot.requestsInWindow.sum() > hotThreshold
    }

    /**
     * Attempt to consume one slot from the local pre-counted budget.
     * Returns true if the request is served locally (skip Redis).
     * Returns false if the budget is exhausted — caller must hit Redis.
     */
    fun tryConsumeLocal(key: String): Boolean {
        val slot = slots[key] ?: return false
        val remaining = slot.localBudget.decrementAndGet()
        if (remaining < 0) {
            // Budget exhausted — reset to 0 and return false so caller refreshes from Redis
            slot.localBudget.compareAndSet(remaining, 0L)
            return false
        }
        return true
    }

    /**
     * Called after a successful Redis batch-grant.
     * Adds [amount] slots to the local budget for this key.
     */
    fun grantBudget(key: String, amount: Long) {
        slots.computeIfAbsent(key) { Slot() }.localBudget.addAndGet(amount)
        log.debug("Granted local budget key={} amount={}", key, amount)
    }

    private fun refreshWindow(slot: Slot) {
        val now = System.currentTimeMillis()
        if (now - slot.windowStartMs.get() >= 1_000L) {
            slot.windowStartMs.set(now)
            slot.requestsInWindow.reset()
        }
    }
}
