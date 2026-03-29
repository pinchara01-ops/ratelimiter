package com.rateforge.circuit

import com.rateforge.config.CircuitBreakerState as MetricsCircuitBreakerState
import com.rateforge.config.RateForgeMetrics
import com.rateforge.config.RateForgeProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

enum class CircuitState {
    CLOSED,
    OPEN,
    HALF_OPEN
}

data class CircuitBreakerState(
    val state: CircuitState,
    val openedAtMs: Long = 0L
)

@Component
class CircuitBreaker(
    private val properties: RateForgeProperties,
    private val metrics: RateForgeMetrics
) {
    private val log = LoggerFactory.getLogger(CircuitBreaker::class.java)

    private val circuitState: AtomicReference<CircuitBreakerState> =
        AtomicReference(CircuitBreakerState(CircuitState.CLOSED))

    // Sliding window of failure timestamps (epoch ms)
    private val failureTimestamps: ConcurrentLinkedDeque<Long> = ConcurrentLinkedDeque()

    // Track probe request in HALF_OPEN state
    private val probeInFlight: AtomicBoolean = AtomicBoolean(false)

    // Consecutive success counter used in HALF_OPEN state
    private val successCount: AtomicInteger = AtomicInteger(0)

    fun getState(): CircuitState {
        val current = circuitState.get()
        val newState = when (current.state) {
            CircuitState.OPEN -> {
                val now = System.currentTimeMillis()
                if (now - current.openedAtMs >= properties.circuitBreaker.probeIntervalMs) {
                    // Transition to HALF_OPEN to allow probe
                    val halfOpen = CircuitBreakerState(CircuitState.HALF_OPEN, current.openedAtMs)
                    if (circuitState.compareAndSet(current, halfOpen)) {
                        log.info("Circuit breaker transitioning OPEN -> HALF_OPEN")
                        metrics.setCircuitBreakerState(MetricsCircuitBreakerState.HALF_OPEN)
                        probeInFlight.set(false)
                        successCount.set(0)
                    }
                    CircuitState.HALF_OPEN
                } else {
                    CircuitState.OPEN
                }
            }
            else -> current.state
        }
        
        // Update metrics with current state
        when (newState) {
            CircuitState.CLOSED -> metrics.setCircuitBreakerState(MetricsCircuitBreakerState.CLOSED)
            CircuitState.OPEN -> metrics.setCircuitBreakerState(MetricsCircuitBreakerState.OPEN)
            CircuitState.HALF_OPEN -> metrics.setCircuitBreakerState(MetricsCircuitBreakerState.HALF_OPEN)
        }
        
        return newState
    }

    /**
     * Record a successful Redis operation.
     * If in HALF_OPEN state, only transition to CLOSED after [successThreshold] consecutive successes.
     */
    fun recordSuccess() {
        val current = circuitState.get()
        if (current.state == CircuitState.HALF_OPEN) {
            val count = successCount.incrementAndGet()
            if (count >= properties.circuitBreaker.successThreshold) {
                val closed = CircuitBreakerState(CircuitState.CLOSED)
                if (circuitState.compareAndSet(current, closed)) {
                    log.info("Circuit breaker transitioning HALF_OPEN -> CLOSED (probe succeeded with $count successes)")
                    metrics.setCircuitBreakerState(MetricsCircuitBreakerState.CLOSED)
                    probeInFlight.set(false)
                    successCount.set(0)
                    failureTimestamps.clear()
                }
            }
        }
    }

    /**
     * Record a failed Redis operation.
     * Prunes old failures and checks threshold to potentially trip to OPEN.
     * If in HALF_OPEN state, transition back to OPEN.
     */
    fun recordFailure() {
        val now = System.currentTimeMillis()
        val current = circuitState.get()

        if (current.state == CircuitState.HALF_OPEN) {
            // Probe failed: go back to OPEN, reset success counter
            val open = CircuitBreakerState(CircuitState.OPEN, now)
            if (circuitState.compareAndSet(current, open)) {
                log.warn("Circuit breaker transitioning HALF_OPEN -> OPEN (probe failed)")
                metrics.setCircuitBreakerState(MetricsCircuitBreakerState.OPEN)
                probeInFlight.set(false)
                successCount.set(0)
            }
            return
        }

        if (current.state == CircuitState.CLOSED) {
            failureTimestamps.addLast(now)
            pruneOldFailures(now)

            if (failureTimestamps.size >= properties.circuitBreaker.failureThreshold) {
                val open = CircuitBreakerState(CircuitState.OPEN, now)
                if (circuitState.compareAndSet(current, open)) {
                    log.error("Circuit breaker transitioning CLOSED -> OPEN ({} failures in {}ms window)",
                        failureTimestamps.size, properties.circuitBreaker.windowMs)
                    metrics.setCircuitBreakerState(MetricsCircuitBreakerState.OPEN)
                    successCount.set(0)
                }
            }
        }
    }

    private fun pruneOldFailures(now: Long) {
        val cutoff = now - properties.circuitBreaker.windowMs
        while (failureTimestamps.isNotEmpty() && (failureTimestamps.peekFirst() ?: Long.MAX_VALUE) <= cutoff) {
            failureTimestamps.pollFirst()
        }
    }

    fun isOpen(): Boolean = getState() == CircuitState.OPEN

    fun isClosed(): Boolean = getState() == CircuitState.CLOSED

    /**
     * Execute a Redis operation with circuit breaker protection.
     *
     * When the circuit is OPEN or the probe slot is taken in HALF_OPEN,
     * the [fallback] is called immediately — callers never need to catch
     * a CircuitOpenException.
     *
     * In HALF_OPEN state exactly one probe is allowed at a time; all other
     * concurrent calls receive the fallback.
     */
    fun <T> execute(operation: () -> T, fallback: () -> T): T {
        val current = circuitState.get()

        when (current.state) {
            CircuitState.OPEN -> {
                val now = System.currentTimeMillis()
                if (now - current.openedAtMs >= properties.circuitBreaker.probeIntervalMs) {
                    // Atomically transition to HALF_OPEN and claim the probe slot
                    val halfOpen = CircuitBreakerState(CircuitState.HALF_OPEN, current.openedAtMs)
                    if (circuitState.compareAndSet(current, halfOpen) && probeInFlight.compareAndSet(false, true)) {
                        log.info("Circuit breaker transitioning OPEN -> HALF_OPEN (probe allowed)")
                        metrics.setCircuitBreakerState(MetricsCircuitBreakerState.HALF_OPEN)
                        successCount.set(0)
                        // fall through to execute the probe below
                    } else {
                        log.debug("Circuit breaker OPEN — probe already in flight, using fallback")
                        return fallback()
                    }
                } else {
                    return fallback()
                }
            }
            CircuitState.HALF_OPEN -> {
                // Probe already in flight — other callers get fallback
                if (!probeInFlight.compareAndSet(false, true)) {
                    return fallback()
                }
                // fall through to run the probe
            }
            CircuitState.CLOSED -> { /* proceed */ }
        }

        return try {
            val result = operation()
            recordSuccess()
            result
        } catch (e: Exception) {
            recordFailure()
            fallback()
        } finally {
            // Release probe slot if we were in HALF_OPEN
            if (circuitState.get().state == CircuitState.HALF_OPEN) {
                probeInFlight.set(false)
            }
        }
    }
}

// Kept for source compatibility — no longer thrown by execute(), but may be useful for testing.
class CircuitOpenException(message: String) : RuntimeException(message)
