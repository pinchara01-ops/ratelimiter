package com.rateforge.analytics

import com.rateforge.config.RateForgeMetrics
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Wrapper for events that have failed processing and been moved to the DLQ.
 */
data class DeadLetterEvent(
    val event: DecisionEvent,
    val failedAt: Instant = Instant.now(),
    val retryCount: Int = 0,
    val lastError: String? = null
)

/**
 * Dead Letter Queue for analytics events that fail to persist after max retries.
 * Provides visibility into failed events and allows for manual retry or investigation.
 */
@Component
class DeadLetterQueue(
    private val metrics: RateForgeMetrics
) {
    private val log = LoggerFactory.getLogger(DeadLetterQueue::class.java)
    
    private val queue = ConcurrentLinkedQueue<DeadLetterEvent>()
    private val maxSize = 10_000 // Cap DLQ size to prevent memory issues
    private val size = AtomicInteger(0)
    
    /**
     * Add a failed event to the dead letter queue.
     * If DLQ is at capacity, oldest events are dropped.
     */
    fun add(event: DecisionEvent, retryCount: Int, error: String?) {
        val dlqEvent = DeadLetterEvent(
            event = event,
            failedAt = Instant.now(),
            retryCount = retryCount,
            lastError = error?.take(500) // Truncate long error messages
        )
        
        // Evict oldest if at capacity
        while (size.get() >= maxSize) {
            val evicted = queue.poll()
            if (evicted != null) {
                size.decrementAndGet()
                log.warn("DLQ at capacity, evicting oldest event: clientId={}", evicted.event.clientId)
            }
        }
        
        queue.offer(dlqEvent)
        val currentSize = size.incrementAndGet()
        metrics.setDeadLetterQueueDepth(currentSize.toLong())
        
        log.warn(
            "Event moved to DLQ after {} retries: clientId={} endpoint={} error={}",
            retryCount, event.clientId, event.endpoint, error?.take(100)
        )
    }
    
    /**
     * Get all events currently in the DLQ.
     */
    fun getAll(): List<DeadLetterEvent> = queue.toList()
    
    /**
     * Get the current size of the DLQ.
     */
    fun size(): Int = size.get()
    
    /**
     * Remove and return all events from the DLQ for retry.
     */
    fun drainAll(): List<DeadLetterEvent> {
        val events = mutableListOf<DeadLetterEvent>()
        while (true) {
            val event = queue.poll() ?: break
            events.add(event)
            size.decrementAndGet()
        }
        metrics.setDeadLetterQueueDepth(0)
        return events
    }
    
    /**
     * Clear all events from the DLQ (e.g., after manual review).
     */
    fun clear() {
        queue.clear()
        size.set(0)
        metrics.setDeadLetterQueueDepth(0)
        log.info("DLQ cleared")
    }
    
    /**
     * Remove a specific event from the DLQ by event ID.
     */
    fun remove(eventId: String): Boolean {
        val iterator = queue.iterator()
        while (iterator.hasNext()) {
            val dlqEvent = iterator.next()
            if (dlqEvent.event.id.toString() == eventId) {
                iterator.remove()
                size.decrementAndGet()
                metrics.setDeadLetterQueueDepth(size.get().toLong())
                return true
            }
        }
        return false
    }
}
