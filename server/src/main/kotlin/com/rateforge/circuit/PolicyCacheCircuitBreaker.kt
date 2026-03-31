package com.rateforge.circuit

import com.rateforge.config.RateForgeMetrics
import com.rateforge.config.RateForgeProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Circuit breaker specifically for the PolicyCache database operations.
 * Prevents cascading failures when the database is unavailable.
 * 
 * This is separate from the Redis circuit breaker to allow independent
 * failure handling for different dependencies.
 */
@Component
class PolicyCacheCircuitBreaker(
    private val properties: RateForgeProperties,
    private val metrics: RateForgeMetrics
) {
    private val log = LoggerFactory.getLogger(PolicyCacheCircuitBreaker::class.java)

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
        return when (current.state) {
            CircuitState.OPEN -> {
                val now = System.currentTimeMillis()
                if (now - current.openedAtMs >= properties.policyCache.circuitBreaker.probeIntervalMs) {
                    // Transition to HALF_OPEN to allow probe
                    val halfOpen = CircuitBreakerState(CircuitState.HALF_OPEN, current.openedAtMs)
                    if (circuitState.compareAndSet(current, halfOpen)) {
                        log.info("Policy cache circuit breaker transitioning OPEN -> HALF_OPEN")
                        metrics.incrementPolicyCacheCircuitBreakerTransition("HALF_OPEN")
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
    }

    /**
     * Record a successful database operation.
     * If in HALF_OPEN state, only transition to CLOSED after successThreshold consecutive successes.
     */
    fun recordSuccess() {
        val current = circuitState.get()
        if (current.state == CircuitState.HALF_OPEN) {
            val count = successCount.incrementAndGet()
            if (count >= properties.policyCache.circuitBreaker.successThreshold) {
                val closed = CircuitBreakerState(CircuitState.CLOSED)
                if (circuitState.compareAndSet(current, closed)) {
                    log.info("Policy cache circuit breaker transitioning HALF_OPEN -> CLOSED (probe succeeded with $count successes)")
                    metrics.incrementPolicyCacheCircuitBreakerTransition("CLOSED")
                    probeInFlight.set(false)
                    successCount.set(0)
                    failureTimestamps.clear()
                }
            }
        }
    }

    /**
     * Record a failed database operation.
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
                log.warn("Policy cache circuit breaker transitioning HALF_OPEN -> OPEN (probe failed)")
                metrics.incrementPolicyCacheCircuitBreakerTransition("OPEN")
                probeInFlight.set(false)
                successCount.set(0)
            }
            return
        }

        if (current.state == CircuitState.CLOSED) {
            failureTimestamps.addLast(now)
            pruneOldFailures(now)

            if (failureTimestamps.size >= properties.policyCache.circuitBreaker.failureThreshold) {
                val open = CircuitBreakerState(CircuitState.OPEN, now)
                if (circuitState.compareAndSet(current, open)) {
                    log.error("Policy cache circuit breaker transitioning CLOSED -> OPEN ({} failures in {}ms window)",
                        failureTimestamps.size, properties.policyCache.circuitBreaker.windowMs)
                    metrics.incrementPolicyCacheCircuitBreakerTransition("OPEN")
                    successCount.set(0)
                }
            }
        }
    }

    private fun pruneOldFailures(now: Long) {
        val cutoff = now - properties.policyCache.circuitBreaker.windowMs
        while (failureTimestamps.isNotEmpty() && (failureTimestamps.peekFirst() ?: Long.MAX_VALUE) <= cutoff) {
            failureTimestamps.pollFirst()
        }
    }

    fun isOpen(): Boolean = getState() == CircuitState.OPEN

    fun isClosed(): Boolean = getState() == CircuitState.CLOSED

    /**
     * Execute a database operation with circuit breaker protection.
     *
     * When the circuit is OPEN or the probe slot is taken in HALF_OPEN,
     * the [fallback] is called immediately.
     *
     * In HALF_OPEN state exactly one probe is allowed at a time; all other
     * concurrent calls receive the fallback.
     *
     * @param operation The database operation to execute
     * @param fallback The fallback to use when circuit is open (typically returns cached data)
     */
    fun <T> execute(operation: () -> T, fallback: () -> T): T {
        val current = circuitState.get()

        when (current.state) {
            CircuitState.OPEN -> {
                val now = System.currentTimeMillis()
                if (now - current.openedAtMs >= properties.policyCache.circuitBreaker.probeIntervalMs) {
                    // Atomically transition to HALF_OPEN and claim the probe slot
                    val halfOpen = CircuitBreakerState(CircuitState.HALF_OPEN, current.openedAtMs)
                    if (circuitState.compareAndSet(current, halfOpen) && probeInFlight.compareAndSet(false, true)) {
                        log.info("Policy cache circuit breaker transitioning OPEN -> HALF_OPEN (probe allowed)")
                        metrics.incrementPolicyCacheCircuitBreakerTransition("HALF_OPEN")
                        successCount.set(0)
                        // fall through to execute the probe below
                    } else {
                        log.debug("Policy cache circuit breaker OPEN — probe already in flight, using fallback")
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
