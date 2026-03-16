package com.rateforge.analytics.pipeline

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AnalyticsPipelineTest {

    private fun event(key: String = "user-1") = DecisionEvent(
        clientKey = key,
        endpoint = "/api/search",
        policyId = "policy-abc",
        algorithm = Algorithm.SLIDING_WINDOW,
        decision = DecisionResult.ALLOW,
        latencyMs = 0.8,
    )

    @Test
    fun `publish adds event to batch queue`() {
        val pipeline = AnalyticsPipeline(capacity = 100)
        pipeline.publish(event())
        assertEquals(1, pipeline.queueDepth())
    }

    @Test
    fun `publish multiple events all appear in queue`() {
        val pipeline = AnalyticsPipeline(capacity = 100)
        repeat(10) { pipeline.publish(event("user-$it")) }
        assertEquals(10, pipeline.queueDepth())
    }

    @Test
    fun `overflow drops oldest event not newest`() {
        val pipeline = AnalyticsPipeline(capacity = 3)
        pipeline.publish(event("old-1"))
        pipeline.publish(event("old-2"))
        pipeline.publish(event("old-3"))
        // Queue is now full; this should drop "old-1"
        pipeline.publish(event("new-4"))

        assertEquals(3, pipeline.queueDepth())
        // Verify "old-1" was dropped
        val drained = mutableListOf<DecisionEvent>()
        pipeline.batchQueue.drainTo(drained, 10)
        assertTrue(drained.none { it.clientKey == "old-1" })
        assertTrue(drained.any { it.clientKey == "new-4" })
    }

    @Test
    fun `queue depth reports correct size`() {
        val pipeline = AnalyticsPipeline(capacity = 50)
        assertEquals(0, pipeline.queueDepth())
        pipeline.publish(event())
        pipeline.publish(event())
        assertEquals(2, pipeline.queueDepth())
        pipeline.batchQueue.poll()
        assertEquals(1, pipeline.queueDepth())
    }
}
