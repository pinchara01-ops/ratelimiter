package com.rateforge.policy

import org.springframework.stereotype.Component

@Component
class PolicyMatcher {

    /**
     * Find the first matching policy for the given request parameters.
     * Policies are already sorted by priority (ascending) from the cache.
     * Lower priority number = higher precedence.
     *
     * Matching rules:
     * - clientId: exact match OR "*" wildcard
     * - endpoint: exact match, prefix match (e.g. "/api/*"), OR "*" wildcard
     * - method: exact match (case-insensitive) OR "*" wildcard
     *
     * @param clientId the requesting client's ID
     * @param endpoint the requested endpoint path
     * @param method the HTTP method (GET, POST, etc.)
     * @param policies list of policies sorted by priority ascending
     * @return the first matching Policy, or null if no match found
     */
    fun findMatchingPolicy(
        clientId: String,
        endpoint: String,
        method: String,
        policies: List<Policy>
    ): Policy? {
        return policies.firstOrNull { policy ->
            matchesClientId(policy.clientId, clientId) &&
                matchesEndpoint(policy.endpoint, endpoint) &&
                matchesMethod(policy.method, method)
        }
    }

    private fun matchesClientId(pattern: String, value: String): Boolean {
        return pattern == "*" || pattern == value
    }

    private fun matchesEndpoint(pattern: String, value: String): Boolean {
        if (pattern == "*") return true
        if (pattern == value) return true
        // Prefix match: pattern ending with "/*" or "*"
        if (pattern.endsWith("/*")) {
            val prefix = pattern.dropLast(2)
            return value.startsWith(prefix)
        }
        if (pattern.endsWith("*")) {
            val prefix = pattern.dropLast(1)
            return value.startsWith(prefix)
        }
        return false
    }

    private fun matchesMethod(pattern: String, value: String): Boolean {
        return pattern == "*" || pattern.equals(value, ignoreCase = true)
    }
}
