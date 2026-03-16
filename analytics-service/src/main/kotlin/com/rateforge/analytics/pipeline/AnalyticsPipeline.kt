package com.rateforge.analytics.pipeline

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.ArrayBlockingQueue

/**
 * Central analytics pipeline (RAT-14).
 *
 * Accepts DecisionEvents from the hot path in a non-blocking, fire-and-forget manner.
 * Events are placed onto two structures:
 *   1. [batchQueue]   — a bounded ArrayBlockingQueue consumed by BatchProcessor for PostgreSQL writes.
 *   2. [liveFlow]     — a SharedFlow that AnalyticsServiceImpl subscribes to for StreamDecisions RPC.
 *
 * Queue overflow strategy: drop the oldest entry to prevent back-pressure on the hot path.
 */
@Component
class AnalyticsPipeline(
    private val capacity: Int = QUEUE_CAPACITY,
) {
    private val log = LoggerFactory.getLogger(AnalyticsPipeline::class.java)

    // Bounded queue for batch PostgreSQL inserts
    val batchQueue: ArrayBlockingQueue<DecisionEvent> = ArrayBlockingQueue(capacity)

    // SharedFlow for live streaming — replay=0 so only active subscribers receive events
    private val _liveFlow = MutableSharedFlow<DecisionEvent>(
        replay = 0,
        extraBufferCapacity = LIVE_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val liveFlow: SharedFlow<DecisionEvent> = _liveFlow.asSharedFlow()

    /**
     * Publish an event. Non-blocking — never throws on overflow.
     * Called from the rate-limit hot path.
     */
    fun publish(event: DecisionEvent) {
        // Offer to batch queue; drop oldest if full
        if (!batchQueue.offer(event)) {
            batchQueue.poll()
            batchQueue.offer(event)
            log.warn("Analytics batch queue full — dropped oldest event (key={})", event.clientKey)
        }

        // Emit to live stream (non-blocking; drops silently if no subscribers or buffer full)
        _liveFlow.tryEmit(event)
    }

    fun queueDepth(): Int = batchQueue.size

    companion object {
        const val QUEUE_CAPACITY = 50_000
        const val LIVE_BUFFER = 1_000
    }
}
