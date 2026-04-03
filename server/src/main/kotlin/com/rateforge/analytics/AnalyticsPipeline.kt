package com.rateforge.analytics

import com.rateforge.config.RateForgeMetrics
import com.rateforge.config.RateForgeProperties
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.sql.Timestamp
import java.util.ArrayDeque
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.UUID

/**
 * Wrapper for events with retry tracking.
 */
private data class RetryableEvent(
    val event: DecisionEvent,
    val retryCount: Int = 0
)

@Component
class AnalyticsPipeline(
    private val properties: RateForgeProperties,
    private val jdbcTemplate: JdbcTemplate,
    private val metrics: RateForgeMetrics,
    private val deadLetterQueue: DeadLetterQueue
) {
    private val log = LoggerFactory.getLogger(AnalyticsPipeline::class.java)

    private val queue: ArrayBlockingQueue<RetryableEvent> =
        ArrayBlockingQueue(properties.analytics.queueCapacity)

    private val shuttingDown: AtomicBoolean = AtomicBoolean(false)
    
    // Max retries before moving to DLQ
    private val maxRetries = 3

    /**
     * Enqueue a decision event for async persistence.
     * If the queue is full, the event is dropped (drop-newest behavior by attempting offer).
     */
    fun record(event: DecisionEvent) {
        val retryable = RetryableEvent(event, retryCount = 0)
        if (!queue.offer(retryable)) {
            log.warn("Analytics queue full (capacity={}), dropping event for client={} endpoint={}",
                properties.analytics.queueCapacity, event.clientId, event.endpoint)
            metrics.incrementDroppedEvents()
        }
        // Update queue depth metrics after recording
        metrics.setAnalyticsQueueDepth(queue.size.toLong())
    }

    @Scheduled(fixedDelayString = "\${rateforge.analytics.flush-interval-ms:500}")
    fun scheduledFlush() {
        if (!shuttingDown.get()) {
            flush(properties.analytics.flushBatchSize)
        }
    }

    @PreDestroy
    fun shutdown() {
        log.info("AnalyticsPipeline shutting down, flushing remaining events")
        shuttingDown.set(true)
        var attempts = 0
        val maxAttempts = 3
        while (queue.isNotEmpty() && attempts < maxAttempts) {
            attempts++
            try {
                val batch = mutableListOf<RetryableEvent>()
                queue.drainTo(batch, properties.analytics.flushBatchSize)
                if (batch.isNotEmpty()) {
                    persistBatch(batch.map { it.event })
                }
            } catch (e: Exception) {
                log.error("Shutdown flush attempt $attempts/$maxAttempts failed", e)
            }
        }
        if (queue.isNotEmpty()) {
            log.warn("AnalyticsPipeline shutdown: ${queue.size} events dropped after $maxAttempts failed flush attempts")
        }
    }

    private fun flush(maxBatch: Int) {
        if (queue.isEmpty()) return

        val batch = mutableListOf<RetryableEvent>()
        queue.drainTo(batch, maxBatch)

        if (batch.isEmpty()) return

        try {
            persistBatch(batch.map { it.event })
            log.debug("Flushed {} analytics events to DB", batch.size)
        } catch (e: Exception) {
            log.error("Failed to persist analytics batch of size {}", batch.size, e)
            handleFailedBatch(batch, e.message ?: "Unknown error")
        }
        
        // Update queue depth metrics after flush
        metrics.setAnalyticsQueueDepth(queue.size.toLong())
    }

    /**
     * Handle a failed batch by re-queuing events with incremented retry count
     * or moving them to DLQ if max retries exceeded.
     */
    private fun handleFailedBatch(batch: List<RetryableEvent>, errorMessage: String) {
        batch.forEach { retryable ->
            val newRetryCount = retryable.retryCount + 1
            
            if (newRetryCount >= maxRetries) {
                // Max retries exceeded, move to DLQ
                deadLetterQueue.add(retryable.event, newRetryCount, errorMessage)
                metrics.incrementDlqEvents()
            } else {
                // Re-queue with incremented retry count
                val updatedEvent = retryable.copy(retryCount = newRetryCount)
                if (!queue.offer(updatedEvent)) {
                    // Queue full, move to DLQ immediately
                    deadLetterQueue.add(retryable.event, newRetryCount, "Queue full during retry")
                    metrics.incrementDlqEvents()
                    log.warn("Could not re-queue failed analytics event, moved to DLQ: clientId={}", retryable.event.clientId)
                }
            }
        }
    }

    /**
     * Retry events from the dead letter queue.
     * Returns the number of events successfully re-queued.
     */
    fun retryDeadLetterEvents(): Int {
        val dlqEvents = deadLetterQueue.drainAll()
        var requeued = 0
        
        dlqEvents.forEach { dlqEvent ->
            // Reset retry count when manually retrying from DLQ
            val retryable = RetryableEvent(dlqEvent.event, retryCount = 0)
            if (queue.offer(retryable)) {
                requeued++
            } else {
                // Queue still full, put back in DLQ
                deadLetterQueue.add(dlqEvent.event, dlqEvent.retryCount, "Queue full during DLQ retry")
            }
        }
        
        log.info("Retried {} events from DLQ, {} re-queued successfully", dlqEvents.size, requeued)
        metrics.setAnalyticsQueueDepth(queue.size.toLong())
        return requeued
    }

    private fun persistBatch(events: Collection<DecisionEvent>) {
        val sql = """
            INSERT INTO decision_events (id, client_id, endpoint, method, policy_id, allowed, reason, latency_us, occurred_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()

        jdbcTemplate.batchUpdate(sql, events.map { event ->
            arrayOf(
                event.id,
                event.clientId,
                event.endpoint,
                event.method,
                event.policyId,
                event.allowed,
                event.reason,
                event.latencyUs,
                Timestamp.from(event.occurredAt)
            )
        })
    }

    fun queueSize(): Int = queue.size
    
    fun deadLetterQueueSize(): Int = deadLetterQueue.size()
}
