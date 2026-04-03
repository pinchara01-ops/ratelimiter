package com.rateforge.health

import com.rateforge.circuit.CircuitBreaker
import com.rateforge.circuit.CircuitState
import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.HealthIndicator
import org.springframework.stereotype.Component

/**
 * Health indicator for the circuit breaker state.
 * Reports the current state and provides insight into Redis connectivity.
 */
@Component("circuitBreakerHealth")
class CircuitBreakerHealthIndicator(
    private val circuitBreaker: CircuitBreaker
) : HealthIndicator {
    
    override fun health(): Health {
        val state = circuitBreaker.getState()
        
        return when (state) {
            CircuitState.CLOSED -> {
                Health.up()
                    .withDetail("state", "CLOSED")
                    .withDetail("description", "Circuit is closed, Redis is healthy")
                    .build()
            }
            CircuitState.OPEN -> {
                Health.outOfService()
                    .withDetail("state", "OPEN")
                    .withDetail("description", "Circuit is open, Redis failures detected")
                    .build()
            }
            CircuitState.HALF_OPEN -> {
                Health.unknown()
                    .withDetail("state", "HALF_OPEN")
                    .withDetail("description", "Circuit is testing Redis connectivity")
                    .build()
            }
        }
    }
}
