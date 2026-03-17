package com.rateforge.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.bind.DefaultValue

@ConfigurationProperties(prefix = "rateforge")
data class RateForgeProperties(
    @DefaultValue("FAIL_OPEN")
    val defaultNoMatchBehavior: NoMatchBehaviorConfig = NoMatchBehaviorConfig.FAIL_OPEN,

    @DefaultValue("30000")
    val policyCacheRefreshIntervalMs: Long = 30000L,

    val circuitBreaker: CircuitBreakerProperties = CircuitBreakerProperties(),
    val analytics: AnalyticsProperties = AnalyticsProperties()
) {
    enum class NoMatchBehaviorConfig {
        FAIL_OPEN, FAIL_CLOSED
    }

    data class CircuitBreakerProperties(
        val failureThreshold: Int = 5,
        val windowMs: Long = 10000L,
        val probeIntervalMs: Long = 30000L,
        val successThreshold: Int = 2
    )

    data class AnalyticsProperties(
        val queueCapacity: Int = 10000,
        val flushIntervalMs: Long = 500L,
        val flushBatchSize: Int = 1000
    )
}
