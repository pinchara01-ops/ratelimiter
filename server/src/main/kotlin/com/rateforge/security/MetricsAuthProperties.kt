package com.rateforge.security

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "rateforge.metrics-auth")
data class MetricsAuthProperties(
    /**
     * Username for accessing metrics/prometheus endpoints.
     */
    val user: String = "metrics",
    
    /**
     * Password for accessing metrics/prometheus endpoints.
     * IMPORTANT: Change this in production!
     */
    val password: String = "metrics"
)
