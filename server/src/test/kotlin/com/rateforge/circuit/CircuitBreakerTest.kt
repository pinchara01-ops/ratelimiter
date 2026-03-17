package com.rateforge.circuit

import com.rateforge.config.RateForgeProperties
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicReference

class CircuitBreakerTest {

    private lateinit var circuitBreaker: CircuitBreaker
    private val properties = RateForgeProperties(
        circuitBreaker = RateForgeProperties.CircuitBreakerProperties(
            failureThreshold = 5,
            windowMs = 10000L,
            probeIntervalMs = 30000L,
            successThreshold = 2
        )
    )

    @BeforeEach
    fun setUp() {
        circuitBreaker = CircuitBreaker(properties)
    }

    @Test
    fun `initial state is CLOSED`() {
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitState.CLOSED)
        assertThat(circuitBreaker.isClosed()).isTrue()
        assertThat(circuitBreaker.isOpen()).isFalse()
    }

    @Test
    fun `CLOSED to OPEN on 5th failure within window`() {
        // 4 failures - still CLOSED
        repeat(4) { circuitBreaker.recordFailure() }
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitState.CLOSED)

        // 5th failure - should trip to OPEN
        circuitBreaker.recordFailure()
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitState.OPEN)
        assertThat(circuitBreaker.isOpen()).isTrue()
    }

    @Test
    fun `failures outside window do not count toward threshold`() {
        // Inject old failures that are outside the window
        val failureTimestampsField = CircuitBreaker::class.java.getDeclaredField("failureTimestamps")
        failureTimestampsField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val failureTimestamps = failureTimestampsField.get(circuitBreaker) as ConcurrentLinkedDeque<Long>

        val oldTimestamp = System.currentTimeMillis() - 20000L // 20s ago, outside 10s window
        repeat(4) { failureTimestamps.addLast(oldTimestamp) }

        // Should still be CLOSED with old failures
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitState.CLOSED)

        // New failures counted from fresh window
        repeat(4) { circuitBreaker.recordFailure() }
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitState.CLOSED)

        // 5th new failure trips breaker
        circuitBreaker.recordFailure()
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitState.OPEN)
    }

    @Test
    fun `OPEN to HALF_OPEN after probe interval`() {
        // Trip circuit to OPEN
        repeat(5) { circuitBreaker.recordFailure() }
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitState.OPEN)

        // Set openedAt to past the probe interval
        setOpenedAt(System.currentTimeMillis() - 31000L) // 31s ago

        // State check should transition to HALF_OPEN
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitState.HALF_OPEN)
    }

    @Test
    fun `one success in HALF_OPEN does NOT close circuit when successThreshold is 2`() {
        // Trip to OPEN
        repeat(5) { circuitBreaker.recordFailure() }
        // Force to HALF_OPEN
        setOpenedAt(System.currentTimeMillis() - 31000L)
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitState.HALF_OPEN)

        // Only one success — should NOT close yet
        circuitBreaker.recordSuccess()
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitState.HALF_OPEN)
    }

    @Test
    fun `two consecutive successes in HALF_OPEN close circuit`() {
        // Trip to OPEN
        repeat(5) { circuitBreaker.recordFailure() }
        // Force to HALF_OPEN
        setOpenedAt(System.currentTimeMillis() - 31000L)
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitState.HALF_OPEN)

        // First success — still HALF_OPEN
        circuitBreaker.recordSuccess()
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitState.HALF_OPEN)

        // Second success — should close
        circuitBreaker.recordSuccess()
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitState.CLOSED)
        assertThat(circuitBreaker.isClosed()).isTrue()
    }

    @Test
    fun `failed probe keeps circuit OPEN`() {
        // Trip to OPEN
        repeat(5) { circuitBreaker.recordFailure() }
        // Force to HALF_OPEN
        setOpenedAt(System.currentTimeMillis() - 31000L)
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitState.HALF_OPEN)

        // Failed probe
        circuitBreaker.recordFailure()

        assertThat(circuitBreaker.getState()).isEqualTo(CircuitState.OPEN)
        assertThat(circuitBreaker.isOpen()).isTrue()
    }

    @Test
    fun `failure after partial successes in HALF_OPEN resets success counter and trips OPEN`() {
        // Trip to OPEN
        repeat(5) { circuitBreaker.recordFailure() }
        setOpenedAt(System.currentTimeMillis() - 31000L)
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitState.HALF_OPEN)

        // One success — still HALF_OPEN
        circuitBreaker.recordSuccess()
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitState.HALF_OPEN)

        // Then a failure — back to OPEN
        circuitBreaker.recordFailure()
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitState.OPEN)
    }

    @Test
    fun `execute returns fallback when circuit is OPEN`() {
        // Trip to OPEN
        repeat(5) { circuitBreaker.recordFailure() }
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitState.OPEN)

        val result = circuitBreaker.execute(
            operation = { "should not execute" },
            fallback = { "fallback-result" }
        )
        assertThat(result).isEqualTo("fallback-result")
    }

    @Test
    fun `execute succeeds in CLOSED state`() {
        val result = circuitBreaker.execute(
            operation = { "success" },
            fallback = { "fallback" }
        )
        assertThat(result).isEqualTo("success")
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitState.CLOSED)
    }

    @Test
    fun `execute records failure on exception and returns fallback, may trip breaker`() {
        // 4 previous failures recorded but not through execute
        repeat(4) { circuitBreaker.recordFailure() }
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitState.CLOSED)

        // 5th failure via execute should trip breaker and return fallback
        val result = circuitBreaker.execute<String>(
            operation = { throw RuntimeException("Redis error") },
            fallback = { "fallback-on-error" }
        )
        assertThat(result).isEqualTo("fallback-on-error")
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitState.OPEN)
    }

    @Test
    fun `OPEN state before probe interval does not transition to HALF_OPEN`() {
        repeat(5) { circuitBreaker.recordFailure() }
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitState.OPEN)

        // Probe interval is 30s, check immediately
        // State should remain OPEN (not enough time has passed)
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitState.OPEN)
    }

    @Test
    fun `record success in CLOSED state is a no-op`() {
        circuitBreaker.recordSuccess()
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitState.CLOSED)
    }

    private fun setOpenedAt(timestampMs: Long) {
        val stateField = CircuitBreaker::class.java.getDeclaredField("circuitState")
        stateField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val stateRef = stateField.get(circuitBreaker) as AtomicReference<CircuitBreakerState>
        stateRef.set(CircuitBreakerState(CircuitState.OPEN, timestampMs))
    }
}
