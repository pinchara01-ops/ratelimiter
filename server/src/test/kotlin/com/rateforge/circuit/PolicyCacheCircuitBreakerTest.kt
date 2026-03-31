package com.rateforge.circuit

import com.rateforge.config.RateForgeMetrics
import com.rateforge.config.RateForgeProperties
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

class PolicyCacheCircuitBreakerTest {

    private lateinit var circuitBreaker: PolicyCacheCircuitBreaker
    private lateinit var properties: RateForgeProperties
    private lateinit var metrics: RateForgeMetrics

    @BeforeEach
    fun setup() {
        properties = RateForgeProperties(
            policyCache = RateForgeProperties.PolicyCacheProperties(
                circuitBreaker = RateForgeProperties.PolicyCacheCircuitBreakerProperties(
                    failureThreshold = 3,
                    windowMs = 10000L,
                    probeIntervalMs = 100L, // Short for testing
                    successThreshold = 2
                )
            )
        )
        metrics = RateForgeMetrics(SimpleMeterRegistry())
        circuitBreaker = PolicyCacheCircuitBreaker(properties, metrics)
    }

    @Test
    fun `initial state should be CLOSED`() {
        assertEquals(CircuitState.CLOSED, circuitBreaker.getState())
    }

    @Test
    fun `should transition to OPEN after failure threshold`() {
        // Record failures to trip the circuit
        repeat(3) {
            circuitBreaker.recordFailure()
        }

        assertEquals(CircuitState.OPEN, circuitBreaker.getState())
    }

    @Test
    fun `should use fallback when circuit is OPEN`() {
        // Trip the circuit
        repeat(3) {
            circuitBreaker.recordFailure()
        }

        val operationCalled = AtomicInteger(0)
        val fallbackCalled = AtomicInteger(0)

        val result = circuitBreaker.execute(
            operation = {
                operationCalled.incrementAndGet()
                "operation"
            },
            fallback = {
                fallbackCalled.incrementAndGet()
                "fallback"
            }
        )

        assertEquals("fallback", result)
        assertEquals(0, operationCalled.get())
        assertEquals(1, fallbackCalled.get())
    }

    @Test
    fun `should execute operation when circuit is CLOSED`() {
        val operationCalled = AtomicInteger(0)
        val fallbackCalled = AtomicInteger(0)

        val result = circuitBreaker.execute(
            operation = {
                operationCalled.incrementAndGet()
                "operation"
            },
            fallback = {
                fallbackCalled.incrementAndGet()
                "fallback"
            }
        )

        assertEquals("operation", result)
        assertEquals(1, operationCalled.get())
        assertEquals(0, fallbackCalled.get())
    }

    @Test
    fun `should use fallback when operation throws exception`() {
        val result = circuitBreaker.execute(
            operation = {
                throw RuntimeException("Database error")
            },
            fallback = {
                "fallback"
            }
        )

        assertEquals("fallback", result)
    }

    @Test
    fun `should transition to HALF_OPEN after probe interval`() {
        // Trip the circuit
        repeat(3) {
            circuitBreaker.recordFailure()
        }
        assertEquals(CircuitState.OPEN, circuitBreaker.getState())

        // Wait for probe interval
        Thread.sleep(150)

        // Should transition to HALF_OPEN
        assertEquals(CircuitState.HALF_OPEN, circuitBreaker.getState())
    }

    @Test
    fun `should transition HALF_OPEN to CLOSED after success threshold`() {
        // Trip the circuit and wait for HALF_OPEN
        repeat(3) {
            circuitBreaker.recordFailure()
        }
        Thread.sleep(150)
        assertEquals(CircuitState.HALF_OPEN, circuitBreaker.getState())

        // Record enough successes
        repeat(2) {
            circuitBreaker.recordSuccess()
        }

        assertEquals(CircuitState.CLOSED, circuitBreaker.getState())
    }

    @Test
    fun `should transition HALF_OPEN to OPEN on failure`() {
        // Trip the circuit and wait for HALF_OPEN
        repeat(3) {
            circuitBreaker.recordFailure()
        }
        Thread.sleep(150)
        assertEquals(CircuitState.HALF_OPEN, circuitBreaker.getState())

        // Record a failure
        circuitBreaker.recordFailure()

        assertEquals(CircuitState.OPEN, circuitBreaker.getState())
    }

    @Test
    fun `successful execute should record success`() {
        // Trip the circuit and wait for HALF_OPEN
        repeat(3) {
            circuitBreaker.recordFailure()
        }
        Thread.sleep(150)

        // Execute successfully twice to close the circuit
        circuitBreaker.execute(
            operation = { "success" },
            fallback = { "fallback" }
        )
        
        // Need to wait briefly and check again since first one used the probe
        Thread.sleep(50)
        
        circuitBreaker.execute(
            operation = { "success" },
            fallback = { "fallback" }
        )

        // Should now be closed
        assertTrue(circuitBreaker.isClosed())
    }

    @Test
    fun `isClosed and isOpen should return correct values`() {
        assertTrue(circuitBreaker.isClosed())
        assertFalse(circuitBreaker.isOpen())

        repeat(3) {
            circuitBreaker.recordFailure()
        }

        assertFalse(circuitBreaker.isClosed())
        assertTrue(circuitBreaker.isOpen())
    }
}
