package com.rateforge.ratelimiter.grpc

import com.rateforge.ratelimiter.algorithm.AlgorithmType
import com.rateforge.ratelimiter.algorithm.CheckResult
import com.rateforge.ratelimiter.algorithm.RateLimitAlgorithm
import com.rateforge.ratelimiter.algorithm.StatusResult
import com.rateforge.ratelimiter.grpc.proto.Algorithm as ProtoAlgorithm
import com.rateforge.ratelimiter.grpc.proto.BatchCheckRequest
import com.rateforge.ratelimiter.grpc.proto.BatchCheckResponse
import com.rateforge.ratelimiter.grpc.proto.CheckRequest
import com.rateforge.ratelimiter.grpc.proto.CheckResponse
import com.rateforge.ratelimiter.grpc.proto.LimitStatus
import com.rateforge.ratelimiter.grpc.proto.RateLimitServiceGrpcKt
import com.rateforge.ratelimiter.grpc.proto.StatusRequest
import com.rateforge.ratelimiter.hotkey.LocalPreCounter
import com.rateforge.ratelimiter.policy.Policy
import com.rateforge.ratelimiter.policy.PolicyMatchingEngine
import com.rateforge.ratelimiter.redis.RedisCircuitBreaker
import io.grpc.Status
import net.devh.boot.grpc.server.service.GrpcService
import org.slf4j.LoggerFactory

/**
 * gRPC implementation of RateLimitService (RAT-5, RAT-12, RAT-13).
 *
 * Request flow:
 *   1. Resolve policy via [PolicyMatchingEngine] (RAT-10)
 *   2. Record request for hot-key detection (RAT-19)
 *   3. If hot key AND local budget available → serve without Redis (RAT-19)
 *   4. Otherwise → call algorithm through [RedisCircuitBreaker] (RAT-11)
 *   5. On successful Redis call for hot key → pre-load local budget (RAT-19)
 */
@GrpcService
class RateLimitServiceImpl(
    private val policyEngine: PolicyMatchingEngine,
    private val algorithms: Map<String, RateLimitAlgorithm>,  // keyed by AlgorithmType.name()
    private val circuitBreaker: RedisCircuitBreaker,
    private val preCounter: LocalPreCounter,
) : RateLimitServiceGrpcKt.RateLimitServiceCoroutineImplBase() {

    private val log = LoggerFactory.getLogger(RateLimitServiceImpl::class.java)

    // -------------------------------------------------------------------------
    // CheckLimit — RAT-5
    // -------------------------------------------------------------------------

    override suspend fun checkLimit(request: CheckRequest): CheckResponse {
        val startNs = System.nanoTime()
        val policy  = policyEngine.resolve(
            clientKey = request.clientKey,
            endpoint  = request.endpoint,
            policyId  = request.policyId.ifBlank { null },
        )
        val redisKey = redisKey(policy, request.clientKey)

        // RAT-19: track request for hot-key detection
        preCounter.recordRequest(redisKey)

        // RAT-19: serve from local budget if this is a hot key and budget remains
        if (preCounter.isHotKey(redisKey) && preCounter.tryConsumeLocal(redisKey)) {
            log.debug("Hot-key local serve key={} policy={}", redisKey, policy.id)
            return allowResponse(policy, remaining = -1L, latencyMs = elapsedMs(startNs))
        }

        // RAT-11: execute through circuit breaker
        val algorithm = algorithms[policy.algorithm.name]
            ?: throw Status.INTERNAL.withDescription("No algorithm for ${policy.algorithm}").asRuntimeException()

        val result = circuitBreaker.execute(
            operation = { algorithm.check(redisKey, policy) },
            fallback  = { CheckResult(allowed = true, remaining = -1, resetMs = 0, algorithm = policy.algorithm) },
        )

        // RAT-19: pre-load local budget after a successful Redis grant for hot keys
        if (result.allowed && preCounter.isHotKey(redisKey)) {
            preCounter.grantBudget(redisKey, preCounter.batchSize)
        }

        return CheckResponse.newBuilder()
            .setAllowed(result.allowed)
            .setRemaining(result.remaining)
            .setResetMs(result.resetMs)
            .setPolicyId(policy.id)
            .setAlgorithm(policy.algorithm.toProto())
            .setLatencyMs(elapsedMs(startNs))
            .build()
    }

    // -------------------------------------------------------------------------
    // BatchCheck — RAT-12
    // -------------------------------------------------------------------------

    override suspend fun batchCheck(request: BatchCheckRequest): BatchCheckResponse {
        if (request.requestsList.isEmpty()) {
            return BatchCheckResponse.newBuilder().build()
        }
        val responses = request.requestsList.map { checkLimit(it) }
        return BatchCheckResponse.newBuilder().addAllResponses(responses).build()
    }

    // -------------------------------------------------------------------------
    // GetLimitStatus — RAT-13
    // -------------------------------------------------------------------------

    override suspend fun getLimitStatus(request: StatusRequest): LimitStatus {
        val policy = policyEngine.resolve(
            clientKey = request.clientKey,
            endpoint  = "",
            policyId  = request.policyId.ifBlank { null },
        )
        val redisKey  = redisKey(policy, request.clientKey)
        val algorithm = algorithms[policy.algorithm.name]
            ?: throw Status.INTERNAL.withDescription("No algorithm for ${policy.algorithm}").asRuntimeException()

        val status: StatusResult = circuitBreaker.execute(
            operation = { algorithm.status(redisKey, policy) },
            fallback  = { StatusResult(currentCount = 0, remaining = policy.limit, fillRatio = 0.0, resetMs = 0) },
        )

        return LimitStatus.newBuilder()
            .setClientKey(request.clientKey)
            .setPolicyId(policy.id)
            .setLimit(policy.limit)
            .setCurrentCount(status.currentCount)
            .setRemaining(status.remaining)
            .setFillRatio(status.fillRatio)
            .setResetMs(status.resetMs)
            .setAlgorithm(policy.algorithm.toProto())
            .build()
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun redisKey(policy: Policy, clientKey: String) =
        "rl:${policy.algorithm.name.lowercase()}:${policy.id}:$clientKey"

    private fun allowResponse(policy: Policy, remaining: Long, latencyMs: Double) =
        CheckResponse.newBuilder()
            .setAllowed(true)
            .setRemaining(remaining)
            .setResetMs(0)
            .setPolicyId(policy.id)
            .setAlgorithm(policy.algorithm.toProto())
            .setLatencyMs(latencyMs)
            .build()

    private fun elapsedMs(startNs: Long) = (System.nanoTime() - startNs) / 1_000_000.0

    private fun AlgorithmType.toProto(): ProtoAlgorithm = when (this) {
        AlgorithmType.FIXED_WINDOW   -> ProtoAlgorithm.FIXED_WINDOW
        AlgorithmType.SLIDING_WINDOW -> ProtoAlgorithm.SLIDING_WINDOW
        AlgorithmType.TOKEN_BUCKET   -> ProtoAlgorithm.TOKEN_BUCKET
    }
}
