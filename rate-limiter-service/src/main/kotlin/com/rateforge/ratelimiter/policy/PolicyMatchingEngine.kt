package com.rateforge.ratelimiter.policy

import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.withLock

/**
 * Policy matching engine with priority resolution (RAT-10).
 *
 * Policies are stored in a sorted list (highest priority first).
 * Matching is O(n) over registered policies; for production scale this
 * would be backed by a trie or ConfigService push.
 *
 * Priority resolution rules:
 *  1. If [policyId] is explicitly supplied, use it directly.
 *  2. Otherwise, find the highest-priority policy whose [clientKeyPattern]
 *     and [endpointPattern] both match.
 *  3. Fall back to [DefaultPolicy.INSTANCE] if nothing matches.
 *
 * Thread-safety: register/deregister hold the write lock so all three
 * mutations (removeIf, add, sort) are seen atomically by concurrent readers.
 */
@Component
class PolicyMatchingEngine {

    private val log  = LoggerFactory.getLogger(PolicyMatchingEngine::class.java)
    private val lock = ReentrantReadWriteLock()
    private val policies = mutableListOf<Policy>()   // guarded by lock

    @PostConstruct
    fun init() {
        register(DefaultPolicy.INSTANCE)
        log.info("PolicyMatchingEngine initialised with default policy")
    }

    /** Register (or replace) a policy. Atomic under write lock. */
    fun register(policy: Policy) {
        lock.writeLock().withLock {
            policies.removeIf { it.id == policy.id }
            policies.add(policy)
            policies.sortByDescending { it.priority }
        }
        log.debug("Registered policy id={} priority={}", policy.id, policy.priority)
    }

    /** Remove a policy by ID. Atomic under write lock. */
    fun deregister(policyId: String) {
        val removed = lock.writeLock().withLock { policies.removeIf { it.id == policyId } }
        if (removed) log.debug("Deregistered policy id={}", policyId)
    }

    /**
     * Resolve the best policy for a request.
     *
     * @param clientKey  Identifying key for the caller (e.g. API key, IP).
     * @param endpoint   The request endpoint (e.g. "/api/search").
     * @param policyId   Explicit policy ID hint — takes precedence if non-blank.
     */
    fun resolve(clientKey: String, endpoint: String, policyId: String?): Policy =
        lock.readLock().withLock {
            if (!policyId.isNullOrBlank()) {
                return@withLock policies.firstOrNull { it.id == policyId }
                    ?: run {
                        log.warn("Explicit policyId={} not found, falling back to default", policyId)
                        DefaultPolicy.INSTANCE
                    }
            }
            policies.firstOrNull { policy ->
                (policy.clientKeyPattern == null || policy.clientKeyPattern.matches(clientKey)) &&
                (policy.endpointPattern  == null || policy.endpointPattern.matches(endpoint))
            } ?: DefaultPolicy.INSTANCE
        }

    fun allPolicies(): List<Policy> = lock.readLock().withLock { policies.toList() }
}
