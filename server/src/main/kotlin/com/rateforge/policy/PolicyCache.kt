package com.rateforge.policy

import com.rateforge.circuit.PolicyCacheCircuitBreaker
import com.rateforge.config.RateForgeMetrics
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicReference
import jakarta.annotation.PostConstruct

@Component
class PolicyCache(
    private val policyRepository: PolicyRepository,
    private val metrics: RateForgeMetrics,
    private val circuitBreaker: PolicyCacheCircuitBreaker
) {
    private val log = LoggerFactory.getLogger(PolicyCache::class.java)

    // Sorted by priority ascending (lower number = higher priority)
    private val cachedPolicies: AtomicReference<List<Policy>> = AtomicReference(emptyList())

    @PostConstruct
    fun initialize() {
        // Let exceptions propagate — fail fast on startup
        val policies = policyRepository.findAllEnabledOrderByPriority()
            .map { it.toDomain() }
            .sortedBy { it.priority }
        cachedPolicies.set(policies)
        log.info("PolicyCache initialized with ${policies.size} policies")
        metrics.setPolicyCacheSize(policies.size.toLong())
    }

    @Scheduled(fixedDelayString = "\${rateforge.policy-cache-refresh-interval-ms:30000}")
    fun refresh() {
        // Use circuit breaker to protect against database failures
        circuitBreaker.execute(
            operation = {
                val policies = policyRepository.findAllEnabledOrderByPriority()
                    .map { it.toDomain() }
                    .sortedBy { it.priority }
                cachedPolicies.set(policies)
                log.debug("PolicyCache refreshed: ${policies.size} policies loaded")
                metrics.setPolicyCacheSize(policies.size.toLong())
            },
            fallback = {
                // Circuit is open or operation failed - retain stale cache
                log.warn("PolicyCache refresh skipped — circuit breaker open or database unavailable, retaining stale cache with ${cachedPolicies.get().size} policies")
                metrics.incrementPolicyCacheCircuitBreakerFallback()
            }
        )
    }

    fun getPolicies(): List<Policy> = cachedPolicies.get()

    fun invalidate() {
        refresh()
    }
}
