package com.rateforge.security

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "rateforge.auth")
data class AuthProperties(
    /**
     * Whether authentication is enabled.
     * When disabled, all requests are allowed.
     */
    val enabled: Boolean = false,

    /**
     * List of valid API keys for authentication.
     * Each key should be a secure random string.
     */
    val apiKeys: List<String> = emptyList(),

    /**
     * Header name for API key authentication.
     */
    val apiKeyHeader: String = "x-api-key"
)
