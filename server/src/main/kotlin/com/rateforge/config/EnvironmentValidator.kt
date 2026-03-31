package com.rateforge.config

import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component

/**
 * Validates that required environment variables are properly configured at startup.
 * 
 * Fails fast with clear error messages if critical configuration is missing,
 * rather than failing later with cryptic errors.
 */
@Component
class EnvironmentValidator(
    private val environment: Environment,
    private val rateForgeProperties: RateForgeProperties
) {
    private val log = LoggerFactory.getLogger(EnvironmentValidator::class.java)

    @PostConstruct
    fun validateEnvironment() {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        // Validate database configuration
        validateDatabaseConfig(errors)
        
        // Validate Redis configuration
        validateRedisConfig(errors)
        
        // Validate gRPC port
        validateGrpcConfig(errors)
        
        // Validate security configuration (warnings for defaults)
        validateSecurityConfig(warnings)
        
        // Validate rate forge properties
        validateRateForgeProperties(errors)
        
        // Log warnings
        warnings.forEach { log.warn("⚠️  Configuration Warning: $it") }
        
        // Fail fast if there are errors
        if (errors.isNotEmpty()) {
            val errorMessage = buildString {
                appendLine()
                appendLine("╔══════════════════════════════════════════════════════════════════╗")
                appendLine("║           ENVIRONMENT CONFIGURATION ERRORS                       ║")
                appendLine("╠══════════════════════════════════════════════════════════════════╣")
                errors.forEachIndexed { index, error ->
                    appendLine("║ ${index + 1}. $error".padEnd(69) + "║")
                }
                appendLine("╠══════════════════════════════════════════════════════════════════╣")
                appendLine("║  Please set the required environment variables and restart.      ║")
                appendLine("╚══════════════════════════════════════════════════════════════════╝")
            }
            log.error(errorMessage)
            throw IllegalStateException("Missing required environment configuration. See errors above.")
        }
        
        log.info("✅ Environment configuration validated successfully")
    }

    private fun validateDatabaseConfig(errors: MutableList<String>) {
        val dbHost = environment.getProperty("DB_HOST")
        val dbPassword = environment.getProperty("DB_PASSWORD")
        
        // In production, DB_HOST should not be localhost
        if (isProduction() && (dbHost == null || dbHost == "localhost")) {
            errors.add("DB_HOST must be set to a remote host in production")
        }
        
        // Warn if using default password
        if (dbPassword == "rateforge") {
            // Will be added as warning, not error for dev environments
        }
    }

    private fun validateRedisConfig(errors: MutableList<String>) {
        val redisHost = environment.getProperty("REDIS_HOST")
        
        // In production, REDIS_HOST should not be localhost
        if (isProduction() && (redisHost == null || redisHost == "localhost")) {
            errors.add("REDIS_HOST must be set to a remote host in production")
        }
    }

    private fun validateGrpcConfig(errors: MutableList<String>) {
        val grpcPort = environment.getProperty("GRPC_PORT")
        
        if (grpcPort != null) {
            val port = grpcPort.toIntOrNull()
            if (port == null || port < 1 || port > 65535) {
                errors.add("GRPC_PORT must be a valid port number (1-65535)")
            }
        }
    }

    private fun validateSecurityConfig(warnings: MutableList<String>) {
        val authEnabled = environment.getProperty("AUTH_ENABLED", "false").toBoolean()
        val metricsPassword = environment.getProperty("METRICS_PASSWORD", "metrics")
        
        if (isProduction()) {
            if (!authEnabled) {
                warnings.add("AUTH_ENABLED is false - gRPC endpoints are unauthenticated")
            }
            
            if (metricsPassword == "metrics") {
                warnings.add("METRICS_PASSWORD is using default value - change in production!")
            }
        }
    }

    private fun validateRateForgeProperties(errors: MutableList<String>) {
        // Validate circuit breaker settings
        if (rateForgeProperties.circuitBreaker.failureThreshold < 1) {
            errors.add("circuit-breaker.failure-threshold must be at least 1")
        }
        
        if (rateForgeProperties.circuitBreaker.windowMs < 1000) {
            errors.add("circuit-breaker.window-ms must be at least 1000ms")
        }
        
        if (rateForgeProperties.circuitBreaker.probeIntervalMs < 1000) {
            errors.add("circuit-breaker.probe-interval-ms must be at least 1000ms")
        }
        
        // Validate analytics settings
        if (rateForgeProperties.analytics.queueCapacity < 100) {
            errors.add("analytics.queue-capacity must be at least 100")
        }
        
        if (rateForgeProperties.analytics.flushIntervalMs < 100) {
            errors.add("analytics.flush-interval-ms must be at least 100ms")
        }
        
        if (rateForgeProperties.analytics.flushBatchSize < 1) {
            errors.add("analytics.flush-batch-size must be at least 1")
        }
    }

    private fun isProduction(): Boolean {
        val activeProfiles = environment.activeProfiles
        return activeProfiles.contains("prod") || 
               activeProfiles.contains("production") ||
               environment.getProperty("ENVIRONMENT") == "production"
    }
}
