package com.rateforge.security

import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest
import org.springframework.boot.actuate.health.HealthEndpoint
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.core.userdetails.User
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.provisioning.InMemoryUserDetailsManager
import org.springframework.security.web.SecurityFilterChain

/**
 * Security configuration for Spring actuator/management endpoints.
 * 
 * - Health endpoint is public (needed for load balancer health checks)
 * - Metrics and Prometheus endpoints require basic auth
 * - Credentials configured via METRICS_USER and METRICS_PASSWORD env vars
 */
@Configuration
@EnableWebSecurity
class ActuatorSecurityConfig(
    private val metricsAuthProperties: MetricsAuthProperties
) {

    @Bean
    @Order(1)
    fun actuatorSecurityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .securityMatcher(EndpointRequest.toAnyEndpoint())
            .authorizeHttpRequests { auth ->
                // Health endpoint is always public (for load balancers, k8s probes)
                auth.requestMatchers(EndpointRequest.to(HealthEndpoint::class.java)).permitAll()
                // All other actuator endpoints require authentication
                auth.anyRequest().authenticated()
            }
            .httpBasic { }
            .csrf { it.disable() }
        
        return http.build()
    }

    @Bean
    fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder()

    @Bean
    fun metricsUserDetailsService(passwordEncoder: PasswordEncoder): UserDetailsService {
        val user = User.builder()
            .username(metricsAuthProperties.user)
            .password(passwordEncoder.encode(metricsAuthProperties.password))
            .roles("ACTUATOR")
            .build()
        
        return InMemoryUserDetailsManager(user)
    }
}
