package com.rateforge.config

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.Assertions.*
import org.springframework.core.env.Environment

class EnvironmentValidatorTest {

    @Test
    fun `should pass validation with valid configuration`() {
        val environment = mockk<Environment>()
        val properties = createValidProperties()
        
        every { environment.getProperty("DB_HOST") } returns "localhost"
        every { environment.getProperty("DB_PASSWORD") } returns "secret"
        every { environment.getProperty("REDIS_HOST") } returns "localhost"
        every { environment.getProperty("GRPC_PORT") } returns "9090"
        every { environment.getProperty("AUTH_ENABLED", "false") } returns "true"
        every { environment.getProperty("METRICS_PASSWORD", "metrics") } returns "secret123"
        every { environment.activeProfiles } returns arrayOf()
        every { environment.getProperty("ENVIRONMENT") } returns null
        
        val validator = EnvironmentValidator(environment, properties)
        
        // Should not throw
        assertDoesNotThrow { validator.validateEnvironment() }
    }
    
    @Test
    fun `should fail with invalid GRPC_PORT`() {
        val environment = mockk<Environment>()
        val properties = createValidProperties()
        
        every { environment.getProperty("DB_HOST") } returns "localhost"
        every { environment.getProperty("DB_PASSWORD") } returns "secret"
        every { environment.getProperty("REDIS_HOST") } returns "localhost"
        every { environment.getProperty("GRPC_PORT") } returns "invalid"
        every { environment.getProperty("AUTH_ENABLED", "false") } returns "false"
        every { environment.getProperty("METRICS_PASSWORD", "metrics") } returns "metrics"
        every { environment.activeProfiles } returns arrayOf()
        every { environment.getProperty("ENVIRONMENT") } returns null
        
        val validator = EnvironmentValidator(environment, properties)
        
        val exception = assertThrows<IllegalStateException> { validator.validateEnvironment() }
        assertTrue(exception.message?.contains("Missing required environment configuration") == true)
    }
    
    @Test
    fun `should fail with invalid circuit breaker threshold`() {
        val environment = mockk<Environment>()
        val properties = createInvalidCircuitBreakerProperties()
        
        every { environment.getProperty("DB_HOST") } returns "localhost"
        every { environment.getProperty("DB_PASSWORD") } returns "secret"
        every { environment.getProperty("REDIS_HOST") } returns "localhost"
        every { environment.getProperty("GRPC_PORT") } returns "9090"
        every { environment.getProperty("AUTH_ENABLED", "false") } returns "false"
        every { environment.getProperty("METRICS_PASSWORD", "metrics") } returns "metrics"
        every { environment.activeProfiles } returns arrayOf()
        every { environment.getProperty("ENVIRONMENT") } returns null
        
        val validator = EnvironmentValidator(environment, properties)
        
        val exception = assertThrows<IllegalStateException> { validator.validateEnvironment() }
        assertTrue(exception.message?.contains("Missing required environment configuration") == true)
    }
    
    @Test
    fun `should fail in production with localhost DB_HOST`() {
        val environment = mockk<Environment>()
        val properties = createValidProperties()
        
        every { environment.getProperty("DB_HOST") } returns "localhost"
        every { environment.getProperty("DB_PASSWORD") } returns "secret"
        every { environment.getProperty("REDIS_HOST") } returns "redis.example.com"
        every { environment.getProperty("GRPC_PORT") } returns "9090"
        every { environment.getProperty("AUTH_ENABLED", "false") } returns "true"
        every { environment.getProperty("METRICS_PASSWORD", "metrics") } returns "secret"
        every { environment.activeProfiles } returns arrayOf("prod")
        every { environment.getProperty("ENVIRONMENT") } returns null
        
        val validator = EnvironmentValidator(environment, properties)
        
        val exception = assertThrows<IllegalStateException> { validator.validateEnvironment() }
        assertTrue(exception.message?.contains("Missing required environment configuration") == true)
    }
    
    @Test
    fun `should fail with invalid analytics queue capacity`() {
        val environment = mockk<Environment>()
        val properties = createInvalidAnalyticsProperties()
        
        every { environment.getProperty("DB_HOST") } returns "localhost"
        every { environment.getProperty("DB_PASSWORD") } returns "secret"
        every { environment.getProperty("REDIS_HOST") } returns "localhost"
        every { environment.getProperty("GRPC_PORT") } returns "9090"
        every { environment.getProperty("AUTH_ENABLED", "false") } returns "false"
        every { environment.getProperty("METRICS_PASSWORD", "metrics") } returns "metrics"
        every { environment.activeProfiles } returns arrayOf()
        every { environment.getProperty("ENVIRONMENT") } returns null
        
        val validator = EnvironmentValidator(environment, properties)
        
        val exception = assertThrows<IllegalStateException> { validator.validateEnvironment() }
        assertTrue(exception.message?.contains("Missing required environment configuration") == true)
    }
    
    private fun createValidProperties(): RateForgeProperties {
        return RateForgeProperties(
            circuitBreaker = RateForgeProperties.CircuitBreakerProperties(
                failureThreshold = 5,
                windowMs = 10000,
                probeIntervalMs = 30000,
                successThreshold = 2
            ),
            analytics = RateForgeProperties.AnalyticsProperties(
                queueCapacity = 10000,
                flushIntervalMs = 500,
                flushBatchSize = 1000
            )
        )
    }
    
    private fun createInvalidCircuitBreakerProperties(): RateForgeProperties {
        return RateForgeProperties(
            circuitBreaker = RateForgeProperties.CircuitBreakerProperties(
                failureThreshold = 0, // Invalid: must be >= 1
                windowMs = 10000,
                probeIntervalMs = 30000,
                successThreshold = 2
            ),
            analytics = RateForgeProperties.AnalyticsProperties(
                queueCapacity = 10000,
                flushIntervalMs = 500,
                flushBatchSize = 1000
            )
        )
    }
    
    private fun createInvalidAnalyticsProperties(): RateForgeProperties {
        return RateForgeProperties(
            circuitBreaker = RateForgeProperties.CircuitBreakerProperties(
                failureThreshold = 5,
                windowMs = 10000,
                probeIntervalMs = 30000,
                successThreshold = 2
            ),
            analytics = RateForgeProperties.AnalyticsProperties(
                queueCapacity = 10, // Invalid: must be >= 100
                flushIntervalMs = 500,
                flushBatchSize = 1000
            )
        )
    }
}
