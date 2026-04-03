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
    val timeouts: TimeoutProperties = TimeoutProperties(),
    val policyCache: PolicyCacheProperties = PolicyCacheProperties(),
    val redis: RedisConfigProperties = RedisConfigProperties()
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

    /**
     * Policy cache configuration including circuit breaker settings.
     */
    data class PolicyCacheProperties(
        /** Refresh interval for policy cache in milliseconds */
        val refreshIntervalMs: Long = 30000L,
        /** Circuit breaker configuration for database operations */
        val circuitBreaker: PolicyCacheCircuitBreakerProperties = PolicyCacheCircuitBreakerProperties()
    )

    /**
     * Circuit breaker configuration for policy cache database operations.
     */
    data class PolicyCacheCircuitBreakerProperties(
        /** Number of failures within window to trip the circuit */
        val failureThreshold: Int = 3,
        /** Time window in milliseconds for counting failures */
        val windowMs: Long = 60000L,
        /** Time in milliseconds to wait before probing after circuit opens */
        val probeIntervalMs: Long = 30000L,
        /** Number of consecutive successes required to close the circuit */
        val successThreshold: Int = 2
    )

    data class RedisConfigProperties(
        /** Number of I/O threads for Redis client */
        val ioThreads: Int = Runtime.getRuntime().availableProcessors(),
        /** Number of computation threads for Redis client */
        val computationThreads: Int = Runtime.getRuntime().availableProcessors(),
        /** Connection pool configuration */
        val pool: RedisPoolProperties = RedisPoolProperties()
    )
}

/**
 * Redis connection pool configuration properties.
 * Uses Apache Commons Pool2 under the hood.
 */
data class RedisPoolProperties(
    /** Minimum number of idle connections in the pool */
    val minIdle: Int = 4,
    /** Maximum number of idle connections in the pool */
    val maxIdle: Int = 8,
    /** Maximum total connections in the pool */
    val maxTotal: Int = 16,
    /** Maximum wait time when borrowing a connection (ms) */
    val maxWaitMs: Long = 1000L,
    /** Test connection validity when borrowing */
    val testOnBorrow: Boolean = true,
    /** Test connection validity when returning */
    val testOnReturn: Boolean = false,
    /** Test idle connections periodically */
    val testWhileIdle: Boolean = true,
    /** Time between eviction runs (ms) */
    val timeBetweenEvictionRunsMs: Long = 30000L,
    /** Minimum time a connection can be idle before eviction (ms) */
    val minEvictableIdleTimeMs: Long = 60000L
)
