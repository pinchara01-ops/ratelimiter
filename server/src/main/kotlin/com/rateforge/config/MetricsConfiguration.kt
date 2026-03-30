package com.rateforge.config

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Micrometer metrics configuration for RateForge observability.
 * Defines custom metrics for rate limiting decisions, circuit breaker state,
 * analytics queue, policy cache, and hot-key pre-denial.
 */
@Configuration
class MetricsConfiguration {

    /**
     * Central metrics collector that provides access to all custom metrics.
     */
    @Bean
    fun rateForgeMetrics(meterRegistry: MeterRegistry): RateForgeMetrics {
        return RateForgeMetrics(meterRegistry)
    }
}

/**
 * Centralized metrics holder for RateForge components.
 * Provides type-safe access to all custom metrics with proper tags and naming.
 */
@Component
class RateForgeMetrics(private val meterRegistry: MeterRegistry) {

    // Atomic values for gauge metrics
    private val circuitBreakerState = AtomicInteger(0) // 0=CLOSED, 1=OPEN, 2=HALF_OPEN
    private val analyticsQueueDepth = AtomicLong(0)
    private val policyCacheSize = AtomicLong(0)

    // Decision metrics
    fun recordDecision(algorithm: String, decision: String): Timer.Sample {
        // Counter for total decisions
        Counter.builder("rateforge.decisions.total")
            .tag("algorithm", algorithm)
            .tag("decision", decision)
            .register(meterRegistry)
            .increment()

        // Timer for decision latency - return sample to be stopped later
        return Timer.start(meterRegistry)
    }

    fun recordDecisionLatency(sample: Timer.Sample) {
        sample.stop(Timer.builder("rateforge.decision.latency")
            .description("Rate limiting decision latency")
            .register(meterRegistry))
    }

    // Circuit breaker metrics
    fun setCircuitBreakerState(state: CircuitBreakerState) {
        val numericValue = when (state) {
            CircuitBreakerState.CLOSED -> 0
            CircuitBreakerState.OPEN -> 1
            CircuitBreakerState.HALF_OPEN -> 2
        }
        circuitBreakerState.set(numericValue)
    }

    // Analytics queue metrics
    fun setAnalyticsQueueDepth(depth: Long) {
        analyticsQueueDepth.set(depth)
    }

    fun incrementDroppedEvents() {
        Counter.builder("rateforge.analytics.dropped_events")
            .description("Events dropped due to analytics queue overflow")
            .register(meterRegistry)
            .increment()
    }

    // Policy cache metrics
    fun setPolicyCacheSize(size: Long) {
        policyCacheSize.set(size)
    }

    // Hot-key pre-denial metrics
    fun incrementHotkeyPreDenied() {
        Counter.builder("rateforge.hotkey.pre_denied")
            .description("Requests denied locally before Redis check")
            .register(meterRegistry)
            .increment()
    }

    init {
        // Register gauge metrics that track atomic values
        Gauge.builder("rateforge.circuit_breaker.state") { circuitBreakerState.get().toDouble() }
            .description("Circuit breaker state (0=CLOSED, 1=OPEN, 2=HALF_OPEN)")
            .register(meterRegistry)

        Gauge.builder("rateforge.analytics.queue.depth") { analyticsQueueDepth.get().toDouble() }
            .description("Current analytics queue size")
            .register(meterRegistry)

        Gauge.builder("rateforge.policy_cache.size") { policyCacheSize.get().toDouble() }
            .description("Number of cached policies")
            .register(meterRegistry)
    }
}

/**
 * Enum representing circuit breaker states for metrics.
 */
enum class CircuitBreakerState {
    CLOSED, OPEN, HALF_OPEN
}