package com.rateforge.ratelimiter.redis

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Redis circuit breaker (RAT-11).
 *
 * Protects the hot path from Redis failures using a 3-state FSM:
 *
 *   CLOSED ──(failures ≥ threshold)──▶ OPEN
 *   OPEN   ──(timeout elapsed)──────▶ HALF_OPEN
 *   HALF_OPEN ──(successes ≥ 2)─────▶ CLOSED
 *   HALF_OPEN ──(any failure)────────▶ OPEN
 *
 * When OPEN, [execute] immediately calls the [fallback] (fail-open strategy:
 * allow the request so legitimate traffic isn't blocked during Redis outages).
 */
@Component
class RedisCircuitBreaker(
    private val failureThreshold: Int  = 5,
    private val successThreshold: Int  = 2,
    private val openTimeoutMs: Long    = 30_000L,
) {
    private val log = LoggerFactory.getLogger(RedisCircuitBreaker::class.java)

    enum class State { CLOSED, OPEN, HALF_OPEN }

    private val state        = AtomicReference(State.CLOSED)
    private val failureCount = AtomicInteger(0)
    private val successCount = AtomicInteger(0)
    private val openedAt     = AtomicLong(0L)

    fun currentState(): State = state.get()

    fun <T> execute(operation: () -> T, fallback: () -> T): T =
        when (state.get()) {
            State.CLOSED    -> runClosed(operation, fallback)
            State.OPEN      -> runOpen(operation, fallback)
            State.HALF_OPEN -> runHalfOpen(operation, fallback)
        }

    // -------------------------------------------------------------------------

    private fun <T> runClosed(operation: () -> T, fallback: () -> T): T = try {
        val result = operation()
        failureCount.set(0)
        result
    } catch (ex: Exception) {
        val failures = failureCount.incrementAndGet()
        log.warn("Redis failure {}/{}: {}", failures, failureThreshold, ex.message)
        if (failures >= failureThreshold) trip()
        fallback()
    }

    private fun <T> runOpen(operation: () -> T, fallback: () -> T): T {
        if (System.currentTimeMillis() - openedAt.get() >= openTimeoutMs) {
            log.info("Circuit breaker → HALF_OPEN (probing Redis)")
            state.compareAndSet(State.OPEN, State.HALF_OPEN)
            return runHalfOpen(operation, fallback)
        }
        return fallback()
    }

    private fun <T> runHalfOpen(operation: () -> T, fallback: () -> T): T = try {
        val result = operation()
        if (successCount.incrementAndGet() >= successThreshold) {
            log.info("Circuit breaker → CLOSED (Redis healthy)")
            reset()
        }
        result
    } catch (ex: Exception) {
        log.warn("Redis probe failed in HALF_OPEN — reopening circuit: {}", ex.message)
        trip()
        fallback()
    }

    private fun trip() {
        log.error("Circuit breaker → OPEN (Redis unreachable)")
        state.set(State.OPEN)
        openedAt.set(System.currentTimeMillis())
        failureCount.set(0)
        successCount.set(0)
    }

    private fun reset() {
        state.set(State.CLOSED)
        failureCount.set(0)
        successCount.set(0)
    }
}
