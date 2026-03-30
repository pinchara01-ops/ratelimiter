package com.rateforge.grpc

import com.rateforge.algorithm.AlgorithmType
import com.rateforge.algorithm.FixedWindowExecutor
import com.rateforge.algorithm.RateLimitResult
import com.rateforge.algorithm.SlidingWindowExecutor
import com.rateforge.algorithm.TokenBucketExecutor
import com.rateforge.analytics.AnalyticsPipeline
import com.rateforge.analytics.DecisionReason
import com.rateforge.circuit.CircuitBreaker
import com.rateforge.circuit.CircuitState
import com.rateforge.config.RateForgeMetrics
import com.rateforge.config.RateForgeProperties
import com.rateforge.hotkey.LocalPreCounter
import com.rateforge.policy.NoMatchBehavior
import com.rateforge.policy.Policy
import com.rateforge.policy.PolicyCache
import com.rateforge.policy.PolicyMatcher
import com.rateforge.proto.RateLimiterServiceGrpcKt
import com.rateforge.proto.batchCheckRequest
import com.rateforge.proto.checkLimitRequest
import com.rateforge.proto.getLimitStatusRequest
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import io.grpc.testing.GrpcCleanupRule
import io.micrometer.core.instrument.Timer
import io.mockk.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.Assertions.*
import java.time.Instant
import java.util.*

class RateLimiterGrpcServiceTest {
    
    private val grpcCleanup = GrpcCleanupRule()
    
    // Mocked dependencies
    private val circuitBreaker = mockk<CircuitBreaker>()
    private val policyCache = mockk<PolicyCache>()
    private val policyMatcher = mockk<PolicyMatcher>()
    private val fixedWindowExecutor = mockk<FixedWindowExecutor>()
    private val slidingWindowExecutor = mockk<SlidingWindowExecutor>()
    private val tokenBucketExecutor = mockk<TokenBucketExecutor>()
    private val analyticsPipeline = mockk<AnalyticsPipeline>()
    private val localPreCounter = mockk<LocalPreCounter>()
    private val metrics = mockk<RateForgeMetrics>()
    
    // Real properties configuration
    private val properties = RateForgeProperties(
        defaultNoMatchBehavior = RateForgeProperties.NoMatchBehaviorConfig.FAIL_OPEN,
        policyCacheRefreshIntervalMs = 30000L,
        circuitBreaker = RateForgeProperties.CircuitBreakerProperties(
            failureThreshold = 5,
            windowMs = 10000L,
            probeIntervalMs = 30000L,
            successThreshold = 2
        ),
        analytics = RateForgeProperties.AnalyticsProperties(
            queueCapacity = 10000,
            flushIntervalMs = 500L,
            flushBatchSize = 1000
        )
    )
    
    private lateinit var service: RateLimiterGrpcService
    private lateinit var client: RateLimiterServiceGrpcKt.RateLimiterServiceCoroutineStub
    
    @BeforeEach
    fun setup() {
        clearAllMocks()
        
        service = RateLimiterGrpcService(
            circuitBreaker = circuitBreaker,
            policyCache = policyCache,
            policyMatcher = policyMatcher,
            fixedWindowExecutor = fixedWindowExecutor,
            slidingWindowExecutor = slidingWindowExecutor,
            tokenBucketExecutor = tokenBucketExecutor,
            analyticsPipeline = analyticsPipeline,
            properties = properties,
            localPreCounter = localPreCounter,
            metrics = metrics
        )
        
        val serverName = InProcessServerBuilder.generateName()
        grpcCleanup.register(
            InProcessServerBuilder
                .forName(serverName)
                .directExecutor()
                .addService(service)
                .build()
                .start()
        )
        
        val channel = grpcCleanup.register(
            InProcessChannelBuilder.forName(serverName)
                .directExecutor()
                .build()
        )
        
        client = RateLimiterServiceGrpcKt.RateLimiterServiceCoroutineStub(channel)
        
        // Setup default mock behaviors
        every { analyticsPipeline.record(any()) } just Runs
        every { localPreCounter.recordRequest(any()) } just Runs
        val timerSample = mockk<Timer.Sample>()
        every { metrics.recordDecision(any(), any()) } returns timerSample
        every { metrics.recordDecisionLatency(any()) } just Runs
        every { metrics.incrementHotkeyPreDenied() } just Runs
    }
    
    @AfterEach
    fun teardown() {
        grpcCleanup.tearDown()
    }
    
    @Test
    fun `checkLimit should return allowed when policy matches and under limit`() = runBlocking {
        // Given
        val policy = createTestPolicy()
        val request = checkLimitRequest {
            clientId = "test-client"
            endpoint = "/api/test"
            method = "GET"
        }
        
        every { circuitBreaker.getState() } returns CircuitState.CLOSED
        every { policyCache.getPolicies() } returns listOf(policy)
        every { policyMatcher.findMatchingPolicy(any(), any(), any(), any()) } returns policy
        every { localPreCounter.isHotKey(any()) } returns false
        every { circuitBreaker.execute<RateLimitResult>(any(), any()) } answers {
            val operation = firstArg<() -> RateLimitResult>()
            operation()
        }
        every { fixedWindowExecutor.checkLimit(any(), any(), any(), any(), any(), any()) } returns 
            RateLimitResult(allowed = true, remaining = 5, resetAtMs = System.currentTimeMillis() + 60000)
        
        // When
        val response = client.checkLimit(request)
        
        // Then
        assertTrue(response.allowed)
        assertEquals(5, response.remaining)
        assertEquals(DecisionReason.ALLOWED, response.reason)
        assertEquals(policy.id.toString(), response.policyId)
        
        verify { analyticsPipeline.record(any()) }
        verify { metrics.recordDecision(policy.algorithm.name, "allowed") }
    }
    
    @Test
    fun `checkLimit should return denied when over limit`() = runBlocking {
        // Given
        val policy = createTestPolicy()
        val request = checkLimitRequest {
            clientId = "test-client"
            endpoint = "/api/test"
            method = "GET"
        }
        
        every { circuitBreaker.getState() } returns CircuitState.CLOSED
        every { policyCache.getPolicies() } returns listOf(policy)
        every { policyMatcher.findMatchingPolicy(any(), any(), any(), any()) } returns policy
        every { localPreCounter.isHotKey(any()) } returns false
        every { circuitBreaker.execute<RateLimitResult>(any(), any()) } answers {
            val operation = firstArg<() -> RateLimitResult>()
            operation()
        }
        every { fixedWindowExecutor.checkLimit(any(), any(), any(), any(), any(), any()) } returns 
            RateLimitResult(allowed = false, remaining = 0, resetAtMs = System.currentTimeMillis() + 60000)
        
        // When
        val response = client.checkLimit(request)
        
        // Then
        assertFalse(response.allowed)
        assertEquals(0, response.remaining)
        assertEquals(DecisionReason.RATE_LIMITED, response.reason)
        
        verify { analyticsPipeline.record(any()) }
        verify { metrics.recordDecision(policy.algorithm.name, "rate_limited") }
    }
    
    @Test
    fun `checkLimit should fail-open when circuit breaker is OPEN`() = runBlocking {
        // Given
        val request = checkLimitRequest {
            clientId = "test-client"
            endpoint = "/api/test"
            method = "GET"
        }
        
        every { circuitBreaker.getState() } returns CircuitState.OPEN
        
        // When
        val response = client.checkLimit(request)
        
        // Then
        assertTrue(response.allowed) // FAIL_OPEN behavior
        assertEquals(0, response.remaining)
        assertEquals(DecisionReason.CIRCUIT_OPEN, response.reason)
        assertEquals("", response.policyId)
        
        verify { analyticsPipeline.record(any()) }
        verify { metrics.recordDecision("", "circuit_open") }
        verify(exactly = 0) { policyCache.getPolicies() }
    }
    
    @Test
    fun `checkLimit should fail-closed when policy has FAIL_CLOSED strategy and circuit is OPEN`() = runBlocking {
        // Given
        val policy = createTestPolicy(noMatchBehavior = NoMatchBehavior.FAIL_CLOSED)
        val properties = RateForgeProperties(
            defaultNoMatchBehavior = RateForgeProperties.NoMatchBehaviorConfig.FAIL_CLOSED
        )
        
        val serviceWithFailClosed = RateLimiterGrpcService(
            circuitBreaker, policyCache, policyMatcher,
            fixedWindowExecutor, slidingWindowExecutor, tokenBucketExecutor,
            analyticsPipeline, properties, localPreCounter, metrics
        )
        
        val request = checkLimitRequest {
            clientId = "test-client"
            endpoint = "/api/test"
            method = "GET"
        }
        
        every { circuitBreaker.getState() } returns CircuitState.OPEN
        
        // When
        val response = serviceWithFailClosed.checkLimit(request)
        
        // Then
        assertFalse(response.allowed) // FAIL_CLOSED behavior
        assertEquals(0, response.remaining)
        assertEquals(DecisionReason.CIRCUIT_OPEN, response.reason)
    }
    
    @Test
    fun `checkLimit should allow unknown key when no policy matches and default is FAIL_OPEN`() = runBlocking {
        // Given
        val request = checkLimitRequest {
            clientId = "unknown-client"
            endpoint = "/api/unknown"
            method = "GET"
        }
        
        every { circuitBreaker.getState() } returns CircuitState.CLOSED
        every { policyCache.getPolicies() } returns listOf()
        every { policyMatcher.findMatchingPolicy(any(), any(), any(), any()) } returns null
        
        // When
        val response = client.checkLimit(request)
        
        // Then
        assertTrue(response.allowed) // Default FAIL_OPEN
        assertEquals(-1, response.remaining)
        assertEquals(DecisionReason.NO_POLICY_FAIL_OPEN, response.reason)
        assertEquals("", response.policyId)
        
        verify { analyticsPipeline.record(any()) }
        verify { metrics.recordDecision("", "no_policy_fail_open") }
    }
    
    @Test
    fun `batchCheck should run all checks concurrently with partial success scenario`() = runBlocking {
        // Given
        val policy1 = createTestPolicy(id = UUID.randomUUID(), name = "policy1")
        val policy2 = createTestPolicy(id = UUID.randomUUID(), name = "policy2")
        
        val request = batchCheckRequest {
            addRequests(checkLimitRequest {
                clientId = "client1"
                endpoint = "/api/test1"
                method = "GET"
            })
            addRequests(checkLimitRequest {
                clientId = "client2"  
                endpoint = "/api/test2"
                method = "POST"
            })
            addRequests(checkLimitRequest {
                clientId = "client3"
                endpoint = "/api/unknown"
                method = "GET"
            })
        }
        
        every { circuitBreaker.getState() } returns CircuitState.CLOSED
        every { policyCache.getPolicies() } returns listOf(policy1, policy2)
        every { policyMatcher.findMatchingPolicy("client1", "/api/test1", "GET", any()) } returns policy1
        every { policyMatcher.findMatchingPolicy("client2", "/api/test2", "POST", any()) } returns policy2
        every { policyMatcher.findMatchingPolicy("client3", "/api/unknown", "GET", any()) } returns null
        every { localPreCounter.isHotKey(any()) } returns false
        
        every { circuitBreaker.execute<RateLimitResult>(any(), any()) } answers {
            val operation = firstArg<() -> RateLimitResult>()
            operation()
        }
        every { fixedWindowExecutor.checkLimit(any(), any(), any(), any(), any(), any()) } returnsMany listOf(
            RateLimitResult(allowed = true, remaining = 5, resetAtMs = System.currentTimeMillis() + 60000),
            RateLimitResult(allowed = false, remaining = 0, resetAtMs = System.currentTimeMillis() + 30000)
        )
        
        // When
        val response = client.batchCheck(request)
        
        // Then
        assertEquals(3, response.responsesCount)
        
        // First request: allowed
        val response1 = response.responsesList[0]
        assertTrue(response1.allowed)
        assertEquals(5, response1.remaining)
        assertEquals(DecisionReason.ALLOWED, response1.reason)
        
        // Second request: denied
        val response2 = response.responsesList[1]
        assertFalse(response2.allowed)
        assertEquals(0, response2.remaining)
        assertEquals(DecisionReason.RATE_LIMITED, response2.reason)
        
        // Third request: no policy, fail open
        val response3 = response.responsesList[2]
        assertTrue(response3.allowed)
        assertEquals(-1, response3.remaining)
        assertEquals(DecisionReason.NO_POLICY_FAIL_OPEN, response3.reason)
        
        verify(exactly = 3) { analyticsPipeline.record(any()) }
    }
    
    @Test
    fun `getLimitStatus should return remaining without incrementing counter`() = runBlocking {
        // Given
        val policy = createTestPolicy()
        val request = getLimitStatusRequest {
            clientId = "test-client"
            endpoint = "/api/test"
            method = "GET"
        }
        
        every { policyCache.getPolicies() } returns listOf(policy)
        every { policyMatcher.findMatchingPolicy(any(), any(), any(), any()) } returns policy
        every { fixedWindowExecutor.getRemaining(any(), any(), any(), any(), any()) } returns 10L
        
        // When
        val response = client.getLimitStatus(request)
        
        // Then
        assertEquals("test-client", response.clientId)
        assertEquals("/api/test", response.endpoint)
        assertEquals("GET", response.method)
        assertEquals(10L, response.remaining)
        assertEquals(policy.id.toString(), response.policyId)
        assertTrue(response.policyFound)
        assertTrue(response.resetAtMs > System.currentTimeMillis())
        
        verify(exactly = 0) { analyticsPipeline.record(any()) }
        verify(exactly = 0) { fixedWindowExecutor.checkLimit(any(), any(), any(), any(), any(), any()) }
    }
    
    @Test
    fun `checkLimit should emit analytics event on every decision`() = runBlocking {
        // Given
        val policy = createTestPolicy()
        val request = checkLimitRequest {
            clientId = "analytics-test"
            endpoint = "/api/analytics"
            method = "POST"
        }
        
        val eventSlot = slot<com.rateforge.analytics.DecisionEvent>()
        every { analyticsPipeline.record(capture(eventSlot)) } just Runs
        
        every { circuitBreaker.getState() } returns CircuitState.CLOSED
        every { policyCache.getPolicies() } returns listOf(policy)
        every { policyMatcher.findMatchingPolicy(any(), any(), any(), any()) } returns policy
        every { localPreCounter.isHotKey(any()) } returns false
        every { circuitBreaker.execute<RateLimitResult>(any(), any()) } answers {
            val operation = firstArg<() -> RateLimitResult>()
            operation()
        }
        every { fixedWindowExecutor.checkLimit(any(), any(), any(), any(), any(), any()) } returns 
            RateLimitResult(allowed = true, remaining = 3, resetAtMs = System.currentTimeMillis() + 60000)
        
        // When
        client.checkLimit(request)
        
        // Then
        verify { analyticsPipeline.record(any()) }
        
        val capturedEvent = eventSlot.captured
        assertEquals("analytics-test", capturedEvent.clientId)
        assertEquals("/api/analytics", capturedEvent.endpoint)
        assertEquals("POST", capturedEvent.method)
        assertEquals(policy.id, capturedEvent.policyId)
        assertTrue(capturedEvent.allowed)
        assertEquals(DecisionReason.ALLOWED, capturedEvent.reason)
        assertTrue(capturedEvent.latencyUs > 0)
    }
    
    @Test
    fun `checkLimit should use hot-key pre-denial when key is hot and budget available`() = runBlocking {
        // Given
        val policy = createTestPolicy()
        val request = checkLimitRequest {
            clientId = "hot-client"
            endpoint = "/api/hot"
            method = "GET"
        }
        
        every { circuitBreaker.getState() } returns CircuitState.CLOSED
        every { policyCache.getPolicies() } returns listOf(policy)
        every { policyMatcher.findMatchingPolicy(any(), any(), any(), any()) } returns policy
        every { localPreCounter.isHotKey(any()) } returns true
        every { localPreCounter.tryConsumeLocal(any()) } returns true
        
        // When
        val response = client.checkLimit(request)
        
        // Then
        assertTrue(response.allowed)
        assertEquals(-1, response.remaining) // Unknown without Redis round-trip
        assertEquals(DecisionReason.ALLOWED, response.reason)
        assertEquals(policy.id.toString(), response.policyId)
        
        verify { metrics.incrementHotkeyPreDenied() }
        verify { analyticsPipeline.record(any()) }
        verify(exactly = 0) { circuitBreaker.execute<Any>(any(), any()) }
        verify(exactly = 0) { fixedWindowExecutor.checkLimit(any(), any(), any(), any(), any(), any()) }
    }
    
    @Test
    fun `checkLimit should reject blank clientId`() = runBlocking {
        // Given
        val request = checkLimitRequest {
            clientId = ""
            endpoint = "/api/test"
            method = "GET"
        }
        
        // When & Then
        val exception = assertThrows<StatusRuntimeException> {
            client.checkLimit(request)
        }
        
        assertEquals(Status.INVALID_ARGUMENT.code, exception.status.code)
        assertTrue(exception.message!!.contains("client_id must not be blank"))
    }
    
    @Test
    fun `checkLimit should reject blank endpoint`() = runBlocking {
        // Given
        val request = checkLimitRequest {
            clientId = "test-client"
            endpoint = ""
            method = "GET"
        }
        
        // When & Then
        val exception = assertThrows<StatusRuntimeException> {
            client.checkLimit(request)
        }
        
        assertEquals(Status.INVALID_ARGUMENT.code, exception.status.code)
        assertTrue(exception.message!!.contains("endpoint must not be blank"))
    }
    
    @Test
    fun `batchCheck should handle concurrent load without blocking threads`() = runBlocking {
        // Given - setup for concurrent batch requests
        val policy = createTestPolicy()
        val concurrentBatches = 20
        val itemsPerBatch = 50
        
        every { circuitBreaker.getState() } returns CircuitState.CLOSED
        every { policyCache.getPolicies() } returns listOf(policy)
        every { policyMatcher.findMatchingPolicy(any(), any(), any(), any()) } returns policy
        every { localPreCounter.isHotKey(any()) } returns false
        every { circuitBreaker.execute<RateLimitResult>(any(), any()) } answers {
            val operation = firstArg<() -> RateLimitResult>()
            operation()
        }
        every { fixedWindowExecutor.checkLimit(any(), any(), any(), any(), any(), any()) } returns 
            RateLimitResult(allowed = true, remaining = 5, resetAtMs = System.currentTimeMillis() + 60000)
        
        // When - fire many concurrent batch requests
        val startTime = System.currentTimeMillis()
        val responses = (1..concurrentBatches).map { batchNum ->
            async {
                val request = batchCheckRequest {
                    (1..itemsPerBatch).forEach { itemNum ->
                        addRequests(checkLimitRequest {
                            clientId = "client-$batchNum-$itemNum"
                            endpoint = "/api/test"
                            method = "GET"
                        })
                    }
                }
                client.batchCheck(request)
            }
        }.awaitAll()
        val elapsed = System.currentTimeMillis() - startTime
        
        // Then - all batches should complete successfully
        assertEquals(concurrentBatches, responses.size)
        responses.forEach { response ->
            assertEquals(itemsPerBatch, response.responsesCount)
            response.responsesList.forEach { checkResponse ->
                assertTrue(checkResponse.allowed)
                assertEquals(DecisionReason.ALLOWED, checkResponse.reason)
            }
        }
        
        // Verify total items processed
        val totalItems = concurrentBatches * itemsPerBatch
        verify(exactly = totalItems) { analyticsPipeline.record(any()) }
        
        // Log timing for manual verification (should complete reasonably fast without thread blocking)
        println("Processed $totalItems items across $concurrentBatches concurrent batches in ${elapsed}ms")
    }
    
    @Test
    fun `checkLimit single request latency should not regress under concurrent load`() = runBlocking {
        // Given
        val policy = createTestPolicy()
        val concurrentCalls = 100
        
        every { circuitBreaker.getState() } returns CircuitState.CLOSED
        every { policyCache.getPolicies() } returns listOf(policy)
        every { policyMatcher.findMatchingPolicy(any(), any(), any(), any()) } returns policy
        every { localPreCounter.isHotKey(any()) } returns false
        every { circuitBreaker.execute<RateLimitResult>(any(), any()) } answers {
            val operation = firstArg<() -> RateLimitResult>()
            operation()
        }
        every { fixedWindowExecutor.checkLimit(any(), any(), any(), any(), any(), any()) } returns 
            RateLimitResult(allowed = true, remaining = 5, resetAtMs = System.currentTimeMillis() + 60000)
        
        // When - fire many concurrent single checkLimit requests
        val latencies = (1..concurrentCalls).map { i ->
            async {
                val request = checkLimitRequest {
                    clientId = "client-$i"
                    endpoint = "/api/test"
                    method = "GET"
                }
                val start = System.nanoTime()
                client.checkLimit(request)
                (System.nanoTime() - start) / 1_000_000.0 // Convert to ms
            }
        }.awaitAll()
        
        // Then - verify latency distribution
        val avgLatency = latencies.average()
        val p99Latency = latencies.sorted()[(concurrentCalls * 0.99).toInt() - 1]
        
        println("Single-check latency under concurrent load: avg=${avgLatency}ms, p99=${p99Latency}ms")
        
        // P99 should be reasonable (not blocked by runBlocking anymore)
        assertTrue(p99Latency < 500, "P99 latency should be under 500ms, was ${p99Latency}ms")
        
        verify(exactly = concurrentCalls) { analyticsPipeline.record(any()) }
    }
    
    private fun createTestPolicy(
        id: UUID = UUID.randomUUID(),
        name: String = "test-policy",
        algorithm: AlgorithmType = AlgorithmType.FIXED_WINDOW,
        limit: Long = 10L,
        windowMs: Long = 60000L,
        noMatchBehavior: NoMatchBehavior? = null
    ): Policy {
        return Policy(
            id = id,
            name = name,
            clientId = "*",
            endpoint = "*",
            method = "*",
            algorithm = algorithm,
            limit = limit,
            windowMs = windowMs,
            bucketSize = null,
            refillRate = null,
            cost = 1L,
            priority = 100,
            noMatchBehavior = noMatchBehavior,
            enabled = true,
            createdAt = Instant.now(),
            updatedAt = Instant.now()
        )
    }
}