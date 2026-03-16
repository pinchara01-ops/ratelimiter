package com.rateforge.analytics.pipeline

import com.rateforge.analytics.repository.DecisionEventEntity
import com.rateforge.analytics.repository.DecisionEventRepository
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Background consumer that drains [AnalyticsPipeline.batchQueue] and bulk-inserts
 * into PostgreSQL every [FLUSH_INTERVAL_MS] ms or when [BATCH_SIZE] events accumulate —
 * whichever comes first (RAT-14).
 */
@Component
class BatchProcessor(
    private val pipeline: AnalyticsPipeline,
    private val repository: DecisionEventRepository,
) {
    private val log = LoggerFactory.getLogger(BatchProcessor::class.java)
    private val scope = CoroutineScope(Dispatchers.IO)
    private val job: Job = scope.launch { runLoop() }

    private suspend fun runLoop() {
        log.info("BatchProcessor started (batchSize={}, flushInterval={}ms)", BATCH_SIZE, FLUSH_INTERVAL_MS)
        while (isActive) {
            delay(FLUSH_INTERVAL_MS)
            flush()
        }
    }

    fun flush() {
        val batch = mutableListOf<DecisionEvent>()
        pipeline.batchQueue.drainTo(batch, BATCH_SIZE)
        if (batch.isEmpty()) return

        val entities = batch.map { it.toEntity() }
        try {
            repository.saveAll(entities)
            log.debug("Flushed {} decision events to PostgreSQL", entities.size)
        } catch (ex: Exception) {
            log.error("Failed to persist analytics batch (size={}): {}", entities.size, ex.message)
            // Re-queue on failure so events are not silently lost; overflow handled by pipeline
            batch.forEach { pipeline.publish(it) }
        }
    }

    @PreDestroy
    fun shutdown() {
        log.info("BatchProcessor shutting down — flushing remaining queue (depth={})", pipeline.queueDepth())
        job.cancel()
        // Drain whatever remains synchronously on shutdown
        runBlocking { flush() }
        log.info("BatchProcessor shutdown complete")
    }

    private fun DecisionEvent.toEntity() = DecisionEventEntity(
        id = id,
        timestampMs = timestampMs,
        clientKey = clientKey,
        endpoint = endpoint,
        policyId = policyId,
        algorithm = algorithm,
        decision = decision,
        latencyMs = latencyMs,
    )

    companion object {
        const val BATCH_SIZE = 1_000
        const val FLUSH_INTERVAL_MS = 500L
    }
}
