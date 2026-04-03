package com.rateforge.health

import org.slf4j.LoggerFactory
import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.HealthIndicator
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.stereotype.Component

/**
 * Health indicator for Redis connectivity.
 * Performs a PING command to verify the connection is alive.
 */
@Component("redis")
class RedisHealthIndicator(
    private val connectionFactory: RedisConnectionFactory
) : HealthIndicator {
    
    private val log = LoggerFactory.getLogger(RedisHealthIndicator::class.java)
    
    override fun health(): Health {
        return try {
            val connection = connectionFactory.connection
            val pingResult = connection.ping()
            connection.close()
            
            if (pingResult == "PONG") {
                Health.up()
                    .withDetail("ping", "PONG")
                    .build()
            } else {
                Health.down()
                    .withDetail("ping", pingResult ?: "null")
                    .withDetail("error", "Unexpected ping response")
                    .build()
            }
        } catch (e: Exception) {
            log.warn("Redis health check failed", e)
            Health.down()
                .withDetail("error", e.message ?: "Unknown error")
                .withException(e)
                .build()
        }
    }
}
