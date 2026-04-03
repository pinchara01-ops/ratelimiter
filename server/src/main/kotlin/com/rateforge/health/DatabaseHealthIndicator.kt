package com.rateforge.health

import org.slf4j.LoggerFactory
import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.HealthIndicator
import org.springframework.stereotype.Component
import javax.sql.DataSource

/**
 * Health indicator for PostgreSQL database connectivity.
 * Performs a simple query to verify the connection is alive.
 */
@Component("database")
class DatabaseHealthIndicator(
    private val dataSource: DataSource
) : HealthIndicator {
    
    private val log = LoggerFactory.getLogger(DatabaseHealthIndicator::class.java)
    
    override fun health(): Health {
        return try {
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT 1").use { rs ->
                        if (rs.next() && rs.getInt(1) == 1) {
                            Health.up()
                                .withDetail("database", "PostgreSQL")
                                .withDetail("validation", "SELECT 1")
                                .build()
                        } else {
                            Health.down()
                                .withDetail("error", "Validation query failed")
                                .build()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            log.warn("Database health check failed", e)
            Health.down()
                .withDetail("error", e.message ?: "Unknown error")
                .withException(e)
                .build()
        }
    }
}
