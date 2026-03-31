package com.rateforge.lifecycle

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Configuration properties for graceful shutdown behavior.
 */
@ConfigurationProperties(prefix = "rateforge.shutdown")
data class ShutdownProperties(
    /**
     * Maximum time to wait for in-flight requests to complete during shutdown.
     * Default: 30 seconds
     */
    val timeoutMs: Long = 30000,
    
    /**
     * Grace period before marking the service as unhealthy during shutdown.
     * This allows load balancers to stop routing traffic.
     * Default: 5 seconds
     */
    val gracePeriodMs: Long = 5000
)
