package com.rateforge.sdk

import com.rateforge.proto.BatchCheckRequest
import com.rateforge.proto.BatchCheckResponse
import com.rateforge.proto.CheckLimitRequest
import com.rateforge.proto.CheckLimitResponse
import com.rateforge.proto.GetLimitStatusRequest
import com.rateforge.proto.GetLimitStatusResponse
import com.rateforge.proto.RateLimiterServiceGrpcKt
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.StatusRuntimeException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.util.concurrent.TimeUnit

/**
 * RAT-21: RateForge Kotlin client SDK.
 *
 * Thread-safe. One instance per process. Call [close] on shutdown.
 *
 * Usage:
 * ```kotlin
 * val client = RateForgeClient.create("localhost:9090")
 * val result = client.checkLimit("user-42", "/api/orders", "POST")
 * if (!result.allowed) throw TooManyRequestsException(result.resetAtMs)
 * client.close()
 * ```
 */
class RateForgeClient private constructor(
    private val channel: ManagedChannel,
    private val config: ClientConfig,
) : Closeable {

    private val log = LoggerFactory.getLogger(RateForgeClient::class.java)
    private val stub = RateLimiterServiceGrpcKt.RateLimiterServiceCoroutineStub(channel)
        .withDeadlineAfter(config.deadlineMs, TimeUnit.MILLISECONDS)

    // ── Single check ──────────────────────────────────────────────────────────

    /**
     * Check a single rate-limit request.
     * @param clientId  Identifies the caller (user ID, API key, IP, …)
     * @param endpoint  The resource path being accessed
     * @param method    HTTP method (GET, POST, …)
     * @param cost      Weight of this request (default 1)
     */
    suspend fun checkLimit(
        clientId: String,
        endpoint: String,
        method: String = "GET",
        cost: Long = 1L,
        metadata: Map<String, String> = emptyMap(),
    ): CheckLimitResponse = withRetry("checkLimit") {
        stub.checkLimit(
            CheckLimitRequest.newBuilder()
                .setClientId(clientId)
                .setEndpoint(endpoint)
                .setMethod(method)
                .setCost(cost)
                .putAllMetadata(metadata)
                .build()
        )
    }

    // ── Batch check ───────────────────────────────────────────────────────────

    /**
     * Check multiple requests in a single gRPC call (fan-out on the server).
     * Returns responses in the same order as [requests].
     */
    suspend fun batchCheck(
        requests: List<CheckLimitRequest>,
    ): BatchCheckResponse = withRetry("batchCheck") {
        stub.batchCheck(
            BatchCheckRequest.newBuilder().addAllRequests(requests).build()
        )
    }

    /**
     * DSL-style batch builder.
     * ```kotlin
     * val results = client.batch {
     *   check("user-1", "/api/orders", "POST")
     *   check("user-2", "/api/search", "GET")
     * }
     * ```
     */
    suspend fun batch(block: BatchBuilder.() -> Unit): BatchCheckResponse {
        val builder = BatchBuilder()
        builder.block()
        return batchCheck(builder.build())
    }

    // ── Status ────────────────────────────────────────────────────────────────

    suspend fun getLimitStatus(
        clientId: String, endpoint: String, method: String = "GET",
    ): GetLimitStatusResponse = withRetry("getLimitStatus") {
        stub.getLimitStatus(
            GetLimitStatusRequest.newBuilder()
                .setClientId(clientId).setEndpoint(endpoint).setMethod(method).build()
        )
    }

    // ── Retry helper ──────────────────────────────────────────────────────────

    private suspend fun <T> withRetry(op: String, block: suspend () -> T): T {
        var lastErr: Exception? = null
        repeat(config.maxRetries + 1) { attempt ->
            try { return block() }
            catch (e: StatusRuntimeException) {
                if (!e.status.code.isRetryable() || attempt == config.maxRetries) throw e
                val backoffMs = config.retryBackoffMs * (1 shl attempt)
                log.warn("[$op] attempt ${attempt+1} failed (${e.status.code}), retrying in ${backoffMs}ms")
                kotlinx.coroutines.delay(backoffMs)
                lastErr = e
            }
        }
        throw lastErr!!
    }

    private fun io.grpc.Status.Code.isRetryable() =
        this == io.grpc.Status.Code.UNAVAILABLE || this == io.grpc.Status.Code.DEADLINE_EXCEEDED

    override fun close() {
        channel.shutdown().awaitTermination(5, TimeUnit.SECONDS)
    }

    // ── Factory ───────────────────────────────────────────────────────────────

    companion object {
        /** Connect to a plaintext gRPC server (local / Docker). */
        fun create(target: String, config: ClientConfig = ClientConfig()): RateForgeClient {
            val channel = ManagedChannelBuilder.forTarget(target)
                .usePlaintext()
                .maxInboundMessageSize(4 * 1024 * 1024)
                .build()
            return RateForgeClient(channel, config)
        }

        /** Connect to a TLS-secured gRPC server (production). */
        fun createTls(target: String, config: ClientConfig = ClientConfig()): RateForgeClient {
            val channel = ManagedChannelBuilder.forTarget(target)
                .maxInboundMessageSize(4 * 1024 * 1024)
                .build()
            return RateForgeClient(channel, config)
        }
    }
}

data class ClientConfig(
    /** gRPC per-call deadline */
    val deadlineMs: Long = 2_000L,
    /** Max automatic retries on transient failures */
    val maxRetries: Int = 2,
    /** Base backoff delay (doubles each attempt) */
    val retryBackoffMs: Long = 100L,
)

class BatchBuilder {
    private val requests = mutableListOf<CheckLimitRequest>()

    fun check(
        clientId: String, endpoint: String, method: String = "GET", cost: Long = 1L,
    ) {
        requests += CheckLimitRequest.newBuilder()
            .setClientId(clientId).setEndpoint(endpoint).setMethod(method).setCost(cost).build()
    }

    fun build(): List<CheckLimitRequest> = requests.toList()
}

/** Thrown when you call [RateForgeClient.checkLimit] and the response is denied. */
class RateLimitExceededException(val resetAtMs: Long, policyId: String) :
    RuntimeException("Rate limit exceeded by policy $policyId. Resets at $resetAtMs ms.")
