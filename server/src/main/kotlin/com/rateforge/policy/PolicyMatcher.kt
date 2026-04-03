package com.rateforge.policy

import org.springframework.stereotype.Component

@Component
class PolicyMatcher {

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
