package com.rateforge.lifecycle

import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.HealthIndicator
import org.springframework.stereotype.Component

/**
 * Health indicator that reports unhealthy during graceful shutdown.
 * 
 * This allows Kubernetes readiness probes and load balancers to stop
 * routing traffic to this instance before it shuts down.
 */
@Component("gracefulShutdown")
class GracefulShutdownHealthIndicator(
    private val shutdownManager: GracefulShutdownManager
) : HealthIndicator {
    
    override fun health(): Health {
        val inFlightCount = shutdownManager.getInFlightRequestCount()
        
        return if (shutdownManager.isReady()) {
            Health.up()
                .withDetail("inFlightRequests", inFlightCount)
                .build()
        } else if (shutdownManager.isShuttingDown()) {
            Health.outOfService()
                .withDetail("status", "shutting_down")
                .withDetail("inFlightRequests", inFlightCount)
                .build()
        } else {
            Health.down()
                .withDetail("status", "not_ready")
                .withDetail("inFlightRequests", inFlightCount)
                .build()
        }
    }
}
