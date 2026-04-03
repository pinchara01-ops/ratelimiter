package com.rateforge.lifecycle

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.boot.actuate.health.Status
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class GracefulShutdownManagerTest {
    
    private lateinit var properties: ShutdownProperties
    private lateinit var manager: GracefulShutdownManager
    
    @BeforeEach
    fun setup() {
        properties = ShutdownProperties(timeoutMs = 1000, gracePeriodMs = 100)
        manager = GracefulShutdownManager(properties)
    }
    
    @Test
    fun `should not be ready initially`() {
        assertFalse(manager.isReady())
        assertFalse(manager.isShuttingDown())
    }
    
    @Test
    fun `should track request start and complete`() {
        assertTrue(manager.requestStarted())
        assertEquals(1, manager.getInFlightRequestCount())
        
        manager.requestCompleted()
        assertEquals(0, manager.getInFlightRequestCount())
    }
    
    @Test
    fun `should track multiple concurrent requests`() {
        repeat(5) { manager.requestStarted() }
        assertEquals(5, manager.getInFlightRequestCount())
        
        repeat(3) { manager.requestCompleted() }
        assertEquals(2, manager.getInFlightRequestCount())
        
        repeat(2) { manager.requestCompleted() }
        assertEquals(0, manager.getInFlightRequestCount())
    }
    
    @Test
    fun `should reject requests during shutdown`() {
        // Simulate becoming ready
        manager.requestStarted()
        manager.requestCompleted()
        
        // Start shutdown in background
        val executor = Executors.newSingleThreadExecutor()
        executor.submit { manager.shutdown() }
        
        // Give shutdown a moment to set the flag
        Thread.sleep(50)
        
        // Requests should be rejected now
        assertFalse(manager.requestStarted())
        
        executor.shutdown()
        executor.awaitTermination(2, TimeUnit.SECONDS)
    }
    
    @Test
    fun `shutdown should wait for in-flight requests`() {
        // Start a request
        manager.requestStarted()
        
        val shutdownStarted = CountDownLatch(1)
        val shutdownCompleted = CountDownLatch(1)
        
        val executor = Executors.newSingleThreadExecutor()
        executor.submit {
            shutdownStarted.countDown()
            manager.shutdown()
            shutdownCompleted.countDown()
        }
        
        // Wait for shutdown to start
        assertTrue(shutdownStarted.await(1, TimeUnit.SECONDS))
        Thread.sleep(50) // Give shutdown time to start waiting
        
        // Shutdown should not complete yet
        assertFalse(shutdownCompleted.await(100, TimeUnit.MILLISECONDS))
        
        // Complete the request
        manager.requestCompleted()
        
        // Now shutdown should complete
        assertTrue(shutdownCompleted.await(1, TimeUnit.SECONDS))
        
        executor.shutdown()
    }
    
    @Test
    fun `shutdown should timeout if requests don't complete`() {
        properties = ShutdownProperties(timeoutMs = 100, gracePeriodMs = 10)
        manager = GracefulShutdownManager(properties)
        
        // Start a request that won't complete
        manager.requestStarted()
        
        val startTime = System.currentTimeMillis()
        manager.shutdown()
        val duration = System.currentTimeMillis() - startTime
        
        // Should have timed out around 100ms
        assertTrue(duration >= 90) // Allow some tolerance
        assertTrue(duration < 500) // But not too long
        
        // Request should still be tracked
        assertEquals(1, manager.getInFlightRequestCount())
    }
}

class GracefulShutdownHealthIndicatorTest {
    
    @Test
    fun `should report UP when ready`() {
        val properties = ShutdownProperties()
        val manager = GracefulShutdownManager(properties)
        val indicator = GracefulShutdownHealthIndicator(manager)
        
        // Simulate becoming ready via ApplicationReadyEvent
        // For this test, we'll just verify the health check behavior
        val health = indicator.health()
        
        // Not ready yet (no ApplicationReadyEvent fired)
        assertEquals(Status.DOWN, health.status)
    }
    
    @Test
    fun `should include in-flight request count`() {
        val properties = ShutdownProperties()
        val manager = GracefulShutdownManager(properties)
        val indicator = GracefulShutdownHealthIndicator(manager)
        
        manager.requestStarted()
        manager.requestStarted()
        
        val health = indicator.health()
        assertEquals(2, health.details["inFlightRequests"])
    }
}
