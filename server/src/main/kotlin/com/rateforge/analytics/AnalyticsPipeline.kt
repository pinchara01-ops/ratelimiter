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
import java.util.concurrent.atomic.AtomicBoolean

@Component
class AnalyticsPipeline(
    private val properties: RateForgeProperties,
    private val jdbcTemplate: JdbcTemplate,
    private val metrics: RateForgeMetrics
) {
    private val log = LoggerFactory.getLogger(AnalyticsPipeline::class.java)

    private val queue: ArrayBlockingQueue<DecisionEvent> =
        ArrayBlockingQueue(properties.analytics.queueCapacity)

    private val shuttingDown: AtomicBoolean = AtomicBoolean(false)

    /**
     * Enqueue a decision event for async persistence.
     * If the queue is full, the event is dropped (drop-newest behavior by attempting offer).
     */
    fun record(event: DecisionEvent) {
        if (!queue.offer(event)) {
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
                val batch = mutableListOf<DecisionEvent>()
                queue.drainTo(batch, properties.analytics.flushBatchSize)
                if (batch.isNotEmpty()) {
                    persistBatch(batch)
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

        val batch = ArrayDeque<DecisionEvent>(maxBatch)
        queue.drainTo(batch, maxBatch)

        if (batch.isEmpty()) return

        try {
            persistBatch(batch)
            log.debug("Flushed {} analytics events to DB", batch.size)
        } catch (e: Exception) {
            log.error("Failed to persist analytics batch of size {}", batch.size, e)
            // Re-queue events that failed (best-effort, may drop if queue is full)
            batch.forEach { event ->
                if (!queue.offer(event)) {
                    log.warn("Could not re-queue failed analytics event, dropping: clientId={}", event.clientId)
                    metrics.incrementDroppedEvents()
                }
            }
        }
        
        // Update queue depth metrics after flush
        metrics.setAnalyticsQueueDepth(queue.size.toLong())
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
}
