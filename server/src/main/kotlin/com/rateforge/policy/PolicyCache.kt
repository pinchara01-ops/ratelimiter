package com.rateforge.policy

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicReference
import jakarta.annotation.PostConstruct

@Component
class PolicyCache(
    private val policyRepository: PolicyRepository
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
    }

    @Scheduled(fixedDelayString = "\${rateforge.policy-cache-refresh-interval-ms:30000}")
    fun refresh() {
        try {
            val policies = policyRepository.findAllEnabledOrderByPriority()
                .map { it.toDomain() }
                .sortedBy { it.priority }
            cachedPolicies.set(policies)
            log.debug("PolicyCache refreshed: ${policies.size} policies loaded")
        } catch (e: Exception) {
            log.error("PolicyCache refresh failed — retaining stale cache", e)
            // Do NOT clear the cache on refresh failure
        }
    }

    fun getPolicies(): List<Policy> = cachedPolicies.get()

    fun invalidate() {
        refresh()
    }
}
