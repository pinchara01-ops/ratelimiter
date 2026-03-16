package com.rateforge.analytics.pipeline

import com.rateforge.analytics.repository.DecisionEventEntity
import com.rateforge.analytics.repository.DecisionEventRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BatchProcessorTest {

    private fun event(key: String = "user-1") = DecisionEvent(
        clientKey = key,
        endpoint = "/api/test",
        policyId = "policy-1",
        algorithm = Algorithm.FIXED_WINDOW,
        decision = DecisionResult.DENY,
        latencyMs = 1.2,
    )

    @Test
    fun `flush drains queue and calls saveAll`() {
        val repository = mockk<DecisionEventRepository>()
        val pipeline = AnalyticsPipeline(capacity = 100)
        val processor = BatchProcessor(pipeline, repository)

        val savedSlot = slot<List<DecisionEventEntity>>()
        every { repository.saveAll(capture(savedSlot)) } returns emptyList()

        repeat(5) { pipeline.publish(event("user-$it")) }
        processor.flush()

        verify(exactly = 1) { repository.saveAll(any<List<DecisionEventEntity>>()) }
        assertEquals(5, savedSlot.captured.size)
        assertEquals(0, pipeline.queueDepth())
    }

    @Test
    fun `flush does nothing when queue is empty`() {
        val repository = mockk<DecisionEventRepository>()
        val pipeline = AnalyticsPipeline(capacity = 100)
        val processor = BatchProcessor(pipeline, repository)

        processor.flush()

        verify(exactly = 0) { repository.saveAll(any<List<DecisionEventEntity>>()) }
    }

    @Test
    fun `flush re-queues events on repository failure`() {
        val repository = mockk<DecisionEventRepository>()
        val pipeline = AnalyticsPipeline(capacity = 100)
        val processor = BatchProcessor(pipeline, repository)

        every { repository.saveAll(any<List<DecisionEventEntity>>()) } throws RuntimeException("DB down")

        pipeline.publish(event())
        processor.flush()

        // Event should be re-published back to the pipeline
        assertTrue(pipeline.queueDepth() > 0)
    }
}
