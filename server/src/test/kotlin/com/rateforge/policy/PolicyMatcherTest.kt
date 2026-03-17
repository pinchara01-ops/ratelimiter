package com.rateforge.policy

import com.rateforge.algorithm.AlgorithmType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class PolicyMatcherTest {

    private lateinit var matcher: PolicyMatcher

    private fun policy(
        clientId: String = "*",
        endpoint: String = "*",
        method: String = "*",
        priority: Int = 100,
        enabled: Boolean = true
    ): Policy = Policy(
        id = UUID.randomUUID(),
        name = "policy-${UUID.randomUUID()}",
        clientId = clientId,
        endpoint = endpoint,
        method = method,
        algorithm = AlgorithmType.FIXED_WINDOW,
        limit = 100L,
        windowMs = 60000L,
        bucketSize = null,
        refillRate = null,
        cost = 1L,
        priority = priority,
        noMatchBehavior = null,
        enabled = enabled,
        createdAt = Instant.now(),
        updatedAt = Instant.now()
    )

    @BeforeEach
    fun setUp() {
        matcher = PolicyMatcher()
    }

    @Test
    fun `exact match wins over wildcard`() {
        val wildcardPolicy = policy(clientId = "*", endpoint = "*", method = "*", priority = 50)
        val exactPolicy = policy(clientId = "client1", endpoint = "/api/users", method = "GET", priority = 10)
        // Sort by priority ascending so exact (lower number) is first
        val policies = listOf(exactPolicy, wildcardPolicy)

        val result = matcher.findMatchingPolicy("client1", "/api/users", "GET", policies)

        assertThat(result).isNotNull
        assertThat(result?.id).isEqualTo(exactPolicy.id)
    }

    @Test
    fun `priority ordering - lower priority number wins`() {
        val lowPriority = policy(clientId = "client1", priority = 100)
        val highPriority = policy(clientId = "client1", priority = 1)
        val policies = listOf(highPriority, lowPriority) // already sorted by priority asc

        val result = matcher.findMatchingPolicy("client1", "/api/test", "GET", policies)

        assertThat(result?.id).isEqualTo(highPriority.id)
    }

    @Test
    fun `no match returns null`() {
        val policy1 = policy(clientId = "client1", endpoint = "/api/users")
        val policies = listOf(policy1)

        val result = matcher.findMatchingPolicy("client2", "/api/orders", "GET", policies)

        assertThat(result).isNull()
    }

    @Test
    fun `wildcard clientId matches any client`() {
        val wildcardPolicy = policy(clientId = "*", endpoint = "/api/test", method = "GET", priority = 10)
        val policies = listOf(wildcardPolicy)

        val result1 = matcher.findMatchingPolicy("client1", "/api/test", "GET", policies)
        val result2 = matcher.findMatchingPolicy("client2", "/api/test", "GET", policies)
        val result3 = matcher.findMatchingPolicy("any-random-client", "/api/test", "GET", policies)

        assertThat(result1?.id).isEqualTo(wildcardPolicy.id)
        assertThat(result2?.id).isEqualTo(wildcardPolicy.id)
        assertThat(result3?.id).isEqualTo(wildcardPolicy.id)
    }

    @Test
    fun `wildcard method matches any HTTP method`() {
        val wildcardMethodPolicy = policy(clientId = "client1", endpoint = "/api/test", method = "*")
        val policies = listOf(wildcardMethodPolicy)

        assertThat(matcher.findMatchingPolicy("client1", "/api/test", "GET", policies)).isNotNull
        assertThat(matcher.findMatchingPolicy("client1", "/api/test", "POST", policies)).isNotNull
        assertThat(matcher.findMatchingPolicy("client1", "/api/test", "DELETE", policies)).isNotNull
        assertThat(matcher.findMatchingPolicy("client1", "/api/test", "PUT", policies)).isNotNull
    }

    @Test
    fun `prefix endpoint match works`() {
        val prefixPolicy = policy(clientId = "*", endpoint = "/api/*", method = "*", priority = 50)
        val exactPolicy = policy(clientId = "*", endpoint = "/api/users", method = "*", priority = 10)
        val policies = listOf(exactPolicy, prefixPolicy)

        // Exact match with higher priority
        val result1 = matcher.findMatchingPolicy("client1", "/api/users", "GET", policies)
        assertThat(result1?.id).isEqualTo(exactPolicy.id)

        // Only prefix match
        val result2 = matcher.findMatchingPolicy("client1", "/api/orders", "GET", policies)
        assertThat(result2?.id).isEqualTo(prefixPolicy.id)

        // No match for different prefix
        val result3 = matcher.findMatchingPolicy("client1", "/other/path", "GET", policies)
        assertThat(result3).isNull()
    }

    @Test
    fun `empty policy list returns null`() {
        val result = matcher.findMatchingPolicy("client1", "/api/test", "GET", emptyList())
        assertThat(result).isNull()
    }

    @Test
    fun `method match is case-insensitive`() {
        val getPolicy = policy(clientId = "client1", endpoint = "/api/test", method = "GET")
        val policies = listOf(getPolicy)

        assertThat(matcher.findMatchingPolicy("client1", "/api/test", "get", policies)).isNotNull
        assertThat(matcher.findMatchingPolicy("client1", "/api/test", "Get", policies)).isNotNull
        assertThat(matcher.findMatchingPolicy("client1", "/api/test", "GET", policies)).isNotNull
    }

    @Test
    fun `specific client overrides wildcard at same priority level`() {
        // When priorities differ, lower number wins
        val specificPolicy = policy(clientId = "client1", endpoint = "/api/test", priority = 5)
        val wildcardPolicy = policy(clientId = "*", endpoint = "/api/test", priority = 10)
        val policies = listOf(specificPolicy, wildcardPolicy) // sorted by priority

        val result = matcher.findMatchingPolicy("client1", "/api/test", "GET", policies)
        assertThat(result?.id).isEqualTo(specificPolicy.id)
    }

    @Test
    fun `wildcard endpoint with star matches all endpoints`() {
        val wildcardEndpointPolicy = policy(clientId = "client1", endpoint = "*", method = "GET")
        val policies = listOf(wildcardEndpointPolicy)

        assertThat(matcher.findMatchingPolicy("client1", "/api/test", "GET", policies)).isNotNull
        assertThat(matcher.findMatchingPolicy("client1", "/some/deep/path", "GET", policies)).isNotNull
        assertThat(matcher.findMatchingPolicy("client1", "/", "GET", policies)).isNotNull
    }
}
