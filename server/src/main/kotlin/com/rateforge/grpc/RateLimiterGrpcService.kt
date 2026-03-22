package com.rateforge.grpc

import com.rateforge.algorithm.AlgorithmType
import com.rateforge.algorithm.FixedWindowExecutor
import com.rateforge.algorithm.RateLimitResult
import com.rateforge.algorithm.SlidingWindowExecutor
import com.rateforge.algorithm.TokenBucketExecutor
import com.rateforge.analytics.AnalyticsPipeline
import com.rateforge.analytics.DecisionEvent
import com.rateforge.analytics.DecisionReason
import com.rateforge.circuit.CircuitBreaker
import com.rateforge.circuit.CircuitState
import com.rateforge.config.RateForgeProperties
import com.rateforge.hotkey.LocalPreCounter
import com.rateforge.policy.NoMatchBehavior
import com.rateforge.policy.Policy
import com.rateforge.policy.PolicyCache
import com.rateforge.policy.PolicyMatcher
import com.rateforge.proto.BatchCheckRequest
import com.rateforge.proto.BatchCheckResponse
import com.rateforge.proto.CheckLimitRequest
import com.rateforge.proto.CheckLimitResponse
import com.rateforge.proto.GetLimitStatusRequest
import com.rateforge.proto.GetLimitStatusResponse
import com.rateforge.proto.RateLimiterServiceGrpcKt
import io.grpc.Status
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import net.devh.boot.grpc.server.service.GrpcService
import org.slf4j.LoggerFactory
import java.time.Instant

@GrpcService
class RateLimiterGrpcService(
    private val circuitBreaker: CircuitBreaker,
    private val policyCache: PolicyCache,
    private val policyMatcher: PolicyMatcher,
    private val fixedWindowExecutor: FixedWindowExecutor,
    private val slidingWindowExecutor: SlidingWindowExecutor,
    private val tokenBucketExecutor: TokenBucketExecutor,
    private val analyticsPipeline: AnalyticsPipeline,
    private val properties: RateForgeProperties,
    private val localPreCounter: LocalPreCounter
) : RateLimiterServiceGrpcKt.RateLimiterServiceCoroutineImplBase() {

    private val log = LoggerFactory.getLogger(RateLimiterGrpcService::class.java)

    override suspend fun checkLimit(request: CheckLimitRequest): CheckLimitResponse =
        processSingleCheck(request)

    override suspend fun batchCheck(request: BatchCheckRequest): BatchCheckResponse {
        return try {
            val results = runBlocking {
                coroutineScope {
                    request.requestsList.map { req ->
                        async(Dispatchers.IO) { processSingleCheck(req) }
                    }.awaitAll()
                }
            }
            batchCheckResponse {
                this.addAllResponses(results)
            }
        } catch (e: Exception) {
            log.error("batchCheck failed", e)
            throw io.grpc.StatusRuntimeException(
                Status.INTERNAL.withDescription("batch check failed")
            )
        }
    }

    private suspend fun processSingleCheck(request: CheckLimitRequest): CheckLimitResponse {
        // MAJ-7: Validate inputs
        if (request.clientId.isBlank()) {
            throw io.grpc.StatusRuntimeException(
                Status.INVALID_ARGUMENT.withDescription("client_id must not be blank")
            )
        }
        if (request.endpoint.isBlank()) {
            throw io.grpc.StatusRuntimeException(
                Status.INVALID_ARGUMENT.withDescription("endpoint must not be blank")
            )
        }
        val cost = if (request.cost <= 0) 1L else minOf(request.cost, 10_000L) // cap at 10k to prevent overflow

        val startNs = System.nanoTime()
        val clientId = request.clientId
        val endpoint = request.endpoint
        val method = request.method

        // 1. Check circuit breaker state — if OPEN and probe interval not elapsed, short-circuit
        if (circuitBreaker.getState() == CircuitState.OPEN) {
            val latencyUs = (System.nanoTime() - startNs) / 1000
            val behavior = properties.defaultNoMatchBehavior
            val allowed = behavior == RateForgeProperties.NoMatchBehaviorConfig.FAIL_OPEN
            val reason = DecisionReason.CIRCUIT_OPEN

            recordEvent(clientId, endpoint, method, null, allowed, reason, latencyUs)

            return checkLimitResponse {
                this.allowed = allowed
                remaining = 0
                resetAtMs = 0
                policyId = ""
                this.reason = reason
            }
        }

        // 2. Match policy
        val policies = policyCache.getPolicies()
        val policy = policyMatcher.findMatchingPolicy(clientId, endpoint, method, policies)

        if (policy == null) {
            val latencyUs = (System.nanoTime() - startNs) / 1000
            val defaultBehavior = properties.defaultNoMatchBehavior
            val allowed = defaultBehavior == RateForgeProperties.NoMatchBehaviorConfig.FAIL_OPEN
            val reason = if (allowed) DecisionReason.NO_POLICY_FAIL_OPEN else DecisionReason.NO_POLICY_FAIL_CLOSED

            recordEvent(clientId, endpoint, method, null, allowed, reason, latencyUs)

            return checkLimitResponse {
                this.allowed = allowed
                remaining = -1
                resetAtMs = 0
                policyId = ""
                this.reason = reason
            }
        }

        // 3. Hot-key mitigation: if this key is hot and local budget is available, skip Redis
        val hotKey = "$clientId:$method:$endpoint"
        localPreCounter.recordRequest(hotKey)
        if (localPreCounter.isHotKey(hotKey) && localPreCounter.tryConsumeLocal(hotKey)) {
            val latencyUs = (System.nanoTime() - startNs) / 1000
            recordEvent(clientId, endpoint, method, policy, true, DecisionReason.ALLOWED, latencyUs)
            return checkLimitResponse {
                allowed = true
                remaining = -1  // unknown without Redis round-trip
                resetAtMs = 0
                policyId = policy.id.toString()
                reason = DecisionReason.ALLOWED
            }
        }

        // 4. Execute rate limit algorithm via circuit breaker (operation + fallback)
        val circuitFallback: () -> RateLimitResult = {
            val behavior = policy.noMatchBehavior ?: run {
                when (properties.defaultNoMatchBehavior) {
                    RateForgeProperties.NoMatchBehaviorConfig.FAIL_OPEN -> NoMatchBehavior.FAIL_OPEN
                    RateForgeProperties.NoMatchBehaviorConfig.FAIL_CLOSED -> NoMatchBehavior.FAIL_CLOSED
                }
            }
            val allowed = behavior == NoMatchBehavior.FAIL_OPEN
            // Return a sentinel result that signals circuit-open fallback
            RateLimitResult(allowed = allowed, remaining = 0, resetAtMs = 0)
        }

        val result = circuitBreaker.execute(
            operation = { executeAlgorithm(policy, clientId, endpoint, method, cost) },
            fallback = circuitFallback
        )

        // Determine if the result came from the fallback (remaining==0 && resetAtMs==0 with a policy present)
        // We distinguish circuit-open from a genuine rate-limit by checking if the circuit is now open
        val latencyUs = (System.nanoTime() - startNs) / 1000
        val isCircuitOpen = circuitBreaker.getState() == CircuitState.OPEN || circuitBreaker.getState() == CircuitState.HALF_OPEN

        val reason = when {
            isCircuitOpen && result.remaining == 0L && result.resetAtMs == 0L -> DecisionReason.CIRCUIT_OPEN
            result.allowed -> DecisionReason.ALLOWED
            else -> DecisionReason.RATE_LIMITED
        }

        // If the key was served by Redis successfully and it's hot, grant a local budget batch
        if (!isCircuitOpen && localPreCounter.isHotKey(hotKey)) {
            localPreCounter.grantBudget(hotKey, localPreCounter.batchSize)
        }

        recordEvent(clientId, endpoint, method, policy, result.allowed, reason, latencyUs)

        return checkLimitResponse {
            allowed = result.allowed
            remaining = result.remaining
            resetAtMs = result.resetAtMs
            policyId = policy.id.toString()
            this.reason = reason
        }
    }

    override suspend fun getLimitStatus(request: GetLimitStatusRequest): GetLimitStatusResponse {
        val clientId = request.clientId
        val endpoint = request.endpoint
        val method = request.method

        val policies = policyCache.getPolicies()
        val policy = policyMatcher.findMatchingPolicy(clientId, endpoint, method, policies)

        if (policy == null) {
            return getLimitStatusResponse {
                this.clientId = clientId
                this.endpoint = endpoint
                this.method = method
                remaining = -1
                resetAtMs = 0
                policyId = ""
                policyFound = false
            }
        }

        // MAJ-6: Query Redis for the actual current counter value
        val remaining = try {
            when (policy.algorithm) {
                AlgorithmType.FIXED_WINDOW -> fixedWindowExecutor.getRemaining(
                    clientId = clientId,
                    endpoint = endpoint,
                    method = method,
                    limit = policy.limit,
                    windowMs = policy.windowMs
                )
                AlgorithmType.SLIDING_WINDOW -> slidingWindowExecutor.getRemaining(
                    clientId = clientId,
                    endpoint = endpoint,
                    method = method,
                    limit = policy.limit,
                    windowMs = policy.windowMs
                )
                AlgorithmType.TOKEN_BUCKET -> {
                    val bucketSize = policy.bucketSize
                        ?: throw IllegalStateException("Token bucket policy ${policy.id} missing bucketSize")
                    val refillRate = policy.refillRate
                        ?: throw IllegalStateException("Token bucket policy ${policy.id} missing refillRate")
                    tokenBucketExecutor.getRemaining(
                        clientId = clientId,
                        endpoint = endpoint,
                        method = method,
                        bucketSize = bucketSize,
                        refillRate = refillRate
                    )
                }
                else -> throw IllegalStateException("Unsupported algorithm type: ${policy.algorithm}")
            }
        } catch (e: Exception) {
            log.error("getLimitStatus Redis query failed for client={} endpoint={}", clientId, endpoint, e)
            // Fall back to returning the policy limit on Redis error
            policy.limit
        }

        return getLimitStatusResponse {
            this.clientId = clientId
            this.endpoint = endpoint
            this.method = method
            this.remaining = remaining
            resetAtMs = Instant.now().toEpochMilli() + policy.windowMs
            policyId = policy.id.toString()
            policyFound = true
        }
    }

    private fun executeAlgorithm(policy: Policy, clientId: String, endpoint: String, method: String, cost: Long): RateLimitResult {
        return when (policy.algorithm) {
            AlgorithmType.FIXED_WINDOW -> fixedWindowExecutor.checkLimit(
                clientId = clientId,
                endpoint = endpoint,
                method = method,
                limit = policy.limit,
                windowMs = policy.windowMs,
                cost = cost
            )
            AlgorithmType.SLIDING_WINDOW -> slidingWindowExecutor.checkLimit(
                clientId = clientId,
                endpoint = endpoint,
                method = method,
                limit = policy.limit,
                windowMs = policy.windowMs,
                cost = cost
            )
            AlgorithmType.TOKEN_BUCKET -> {
                val bucketSize = policy.bucketSize
                    ?: throw IllegalStateException("Token bucket policy ${policy.id} missing bucketSize")
                val refillRate = policy.refillRate
                    ?: throw IllegalStateException("Token bucket policy ${policy.id} missing refillRate")
                tokenBucketExecutor.checkLimit(
                    clientId = clientId,
                    endpoint = endpoint,
                    method = method,
                    bucketSize = bucketSize,
                    refillRate = refillRate,
                    cost = cost
                )
            }
            else -> throw IllegalStateException("Unsupported algorithm type: ${policy.algorithm}")
        }
    }

    private fun recordEvent(
        clientId: String,
        endpoint: String,
        method: String,
        policy: Policy?,
        allowed: Boolean,
        reason: String,
        latencyUs: Long
    ) {
        analyticsPipeline.record(
            DecisionEvent(
                clientId = clientId,
                endpoint = endpoint,
                method = method.ifEmpty { null },
                policyId = policy?.id,
                allowed = allowed,
                reason = reason,
                latencyUs = latencyUs
            )
        )
    }

    // DSL builder helpers
    private fun checkLimitResponse(block: CheckLimitResponse.Builder.() -> Unit): CheckLimitResponse =
        CheckLimitResponse.newBuilder().apply(block).build()

    private fun batchCheckResponse(block: BatchCheckResponse.Builder.() -> Unit): BatchCheckResponse =
        BatchCheckResponse.newBuilder().apply(block).build()

    private fun getLimitStatusResponse(block: GetLimitStatusResponse.Builder.() -> Unit): GetLimitStatusResponse =
        GetLimitStatusResponse.newBuilder().apply(block).build()
}
