package com.rateforge.ratelimiter.policy

import com.rateforge.ratelimiter.algorithm.AlgorithmType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class PolicyMatchingEngineTest {

    private val engine = PolicyMatchingEngine()

    @BeforeEach
    fun setup() {
        engine.init()
    }

    private fun policy(
        id: String,
        priority: Int = 0,
        clientPattern: String? = null,
        endpointPattern: String? = null,
    ) = Policy(
        id = id, name = id,
        algorithm = AlgorithmType.FIXED_WINDOW,
        limit = 100L, windowSeconds = 60L,
        priority = priority,
        clientKeyPattern = clientPattern?.toRegex(),
        endpointPattern  = endpointPattern?.toRegex(),
    )

    @Test
    fun `resolves default policy when no match`() {
        val result = engine.resolve("unknown-key", "/api/test", null)
        assertEquals("default", result.id)
    }

    @Test
    fun `resolves policy by explicit policyId`() {
        engine.register(policy("explicit-policy", priority = 1))
        val result = engine.resolve("any-key", "/any-endpoint", "explicit-policy")
        assertEquals("explicit-policy", result.id)
    }

    @Test
    fun `falls back to default for unknown explicit policyId`() {
        val result = engine.resolve("key", "/ep", "non-existent-id")
        assertEquals("default", result.id)
    }

    @Test
    fun `higher priority policy wins over lower priority`() {
        engine.register(policy("low", priority = 1))
        engine.register(policy("high", priority = 10))
        val result = engine.resolve("any-key", "/any", null)
        assertEquals("high", result.id)
    }

    @Test
    fun `client key pattern matches correctly`() {
        engine.register(policy("premium", priority = 5, clientPattern = "premium-.*"))
        val match    = engine.resolve("premium-user-42", "/api/data", null)
        val noMatch  = engine.resolve("free-user-1",     "/api/data", null)
        assertEquals("premium", match.id)
        assertEquals("default", noMatch.id)
    }

    @Test
    fun `endpoint pattern matches correctly`() {
        engine.register(policy("search-policy", priority = 5, endpointPattern = ".*/search.*"))
        val match   = engine.resolve("user-1", "/api/v2/search?q=hello", null)
        val noMatch = engine.resolve("user-1", "/api/v2/data",           null)
        assertEquals("search-policy", match.id)
        assertEquals("default", noMatch.id)
    }

    @Test
    fun `registering policy with same id replaces existing`() {
        engine.register(policy("dup", priority = 1))
        engine.register(policy("dup", priority = 99))  // replaces
        val all = engine.allPolicies().filter { it.id == "dup" }
        assertEquals(1, all.size)
        assertEquals(99, all[0].priority)
    }

    @Test
    fun `deregister removes policy`() {
        engine.register(policy("temp", priority = 5))
        engine.deregister("temp")
        val result = engine.resolve("any", "/any", "temp")
        assertEquals("default", result.id)
    }
}
