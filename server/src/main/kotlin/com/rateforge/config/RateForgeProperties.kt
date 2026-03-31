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
    val analytics: AnalyticsProperties = AnalyticsProperties(),
    val timeouts: TimeoutProperties = TimeoutProperties()
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

    data class TimeoutProperties(
        /** Redis command timeout in milliseconds */
        val redisCommandMs: Long = 100L,
        /** Redis connection timeout in milliseconds */
        val redisConnectMs: Long = 1000L,
        /** Database query timeout in milliseconds */
        val databaseQueryMs: Long = 5000L,
        /** gRPC request deadline in milliseconds (0 = no deadline) */
        val grpcRequestMs: Long = 10000L
    )
}
