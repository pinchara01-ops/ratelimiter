package com.rateforge.analytics

import com.rateforge.config.RateForgeMetrics
import io.mockk.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.time.Instant
import java.util.UUID

class DeadLetterQueueTest {
    
    private val metrics = mockk<RateForgeMetrics>(relaxed = true)
    private lateinit var dlq: DeadLetterQueue
    
    @BeforeEach
    fun setup() {
        clearAllMocks()
        dlq = DeadLetterQueue(metrics)
    }
    
    private fun createTestEvent(clientId: String = "test-client"): DecisionEvent {
        return DecisionEvent(
            id = UUID.randomUUID(),
            clientId = clientId,
            endpoint = "/api/test",
            method = "GET",
            policyId = UUID.randomUUID(),
            allowed = true,
            reason = "ALLOWED",
            latencyUs = 100L,
            occurredAt = Instant.now()
        )
    }
    
    @Test
    fun `add should store event in DLQ`() {
        val event = createTestEvent()
        
        dlq.add(event, retryCount = 3, error = "Database connection failed")
        
        assertEquals(1, dlq.size())
        val events = dlq.getAll()
        assertEquals(1, events.size)
        assertEquals(event.id, events[0].event.id)
        assertEquals(3, events[0].retryCount)
        assertEquals("Database connection failed", events[0].lastError)
        
        verify { metrics.setDeadLetterQueueDepth(1) }
    }
    
    @Test
    fun `add should truncate long error messages`() {
        val event = createTestEvent()
        val longError = "x".repeat(1000)
        
        dlq.add(event, retryCount = 1, error = longError)
        
        val events = dlq.getAll()
        assertEquals(500, events[0].lastError?.length)
    }
    
    @Test
    fun `drainAll should return and remove all events`() {
        val event1 = createTestEvent("client1")
        val event2 = createTestEvent("client2")
        
        dlq.add(event1, retryCount = 1, error = null)
        dlq.add(event2, retryCount = 2, error = null)
        
        assertEquals(2, dlq.size())
        
        val drained = dlq.drainAll()
        
        assertEquals(2, drained.size)
        assertEquals(0, dlq.size())
        verify { metrics.setDeadLetterQueueDepth(0) }
    }
    
    @Test
    fun `clear should remove all events`() {
        dlq.add(createTestEvent(), retryCount = 1, error = null)
        dlq.add(createTestEvent(), retryCount = 1, error = null)
        
        dlq.clear()
        
        assertEquals(0, dlq.size())
        assertTrue(dlq.getAll().isEmpty())
        verify { metrics.setDeadLetterQueueDepth(0) }
    }
    
    @Test
    fun `remove should delete specific event by ID`() {
        val event1 = createTestEvent()
        val event2 = createTestEvent()
        
        dlq.add(event1, retryCount = 1, error = null)
        dlq.add(event2, retryCount = 1, error = null)
        
        val removed = dlq.remove(event1.id.toString())
        
        assertTrue(removed)
        assertEquals(1, dlq.size())
        assertEquals(event2.id, dlq.getAll()[0].event.id)
    }
    
    @Test
    fun `remove should return false for non-existent event`() {
        val event = createTestEvent()
        dlq.add(event, retryCount = 1, error = null)
        
        val removed = dlq.remove(UUID.randomUUID().toString())
        
        assertFalse(removed)
        assertEquals(1, dlq.size())
    }
    
    @Test
    fun `getAll should return copy of events without modifying queue`() {
        val event = createTestEvent()
        dlq.add(event, retryCount = 1, error = null)
        
        val events1 = dlq.getAll()
        val events2 = dlq.getAll()
        
        assertEquals(1, events1.size)
        assertEquals(1, events2.size)
        assertEquals(1, dlq.size())
    }
}
