package com.rateforge.analytics.grpc

import com.rateforge.analytics.grpc.proto.Algorithm as ProtoAlgorithm
import com.rateforge.analytics.grpc.proto.AnalyticsServiceGrpcKt
import com.rateforge.analytics.grpc.proto.ClientStats
import com.rateforge.analytics.grpc.proto.DecisionEvent as ProtoDecisionEvent
import com.rateforge.analytics.grpc.proto.DecisionResult as ProtoDecisionResult
import com.rateforge.analytics.grpc.proto.TimeRange
import com.rateforge.analytics.grpc.proto.TopClientsList
import com.rateforge.analytics.grpc.proto.TopClientsRequest
import com.rateforge.analytics.grpc.proto.UsageStats
import com.rateforge.analytics.grpc.proto.UsageStatsRequest
import com.rateforge.analytics.grpc.proto.streamRequest
import com.rateforge.analytics.pipeline.Algorithm
import com.rateforge.analytics.pipeline.AnalyticsPipeline
import com.rateforge.analytics.pipeline.DecisionResult
import com.rateforge.analytics.repository.DecisionEventRepository
import io.grpc.Status
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import net.devh.boot.grpc.server.service.GrpcService
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.temporal.ChronoUnit

@GrpcService
class AnalyticsServiceImpl(
    private val pipeline: AnalyticsPipeline,
    private val repository: DecisionEventRepository,
) : AnalyticsServiceGrpcKt.AnalyticsServiceCoroutineImplBase() {

    private val log = LoggerFactory.getLogger(AnalyticsServiceImpl::class.java)

    // -------------------------------------------------------------------------
    // GetUsageStats — reads from PostgreSQL materialized data (RAT-15)
    // -------------------------------------------------------------------------

    override suspend fun getUsageStats(request: UsageStatsRequest): UsageStats {
        val sinceMs = request.timeRange.toSinceMs()
        val policyId = request.policyId.takeIf { it.isNotBlank() }
        val clientKey = request.clientKey.takeIf { it.isNotBlank() }

        log.debug("GetUsageStats policyId={} clientKey={} range={}", policyId, clientKey, request.timeRange)

        val stats = try {
            repository.getAggregatedStats(policyId, clientKey, sinceMs)
        } catch (ex: Exception) {
            log.error("GetUsageStats query failed: {}", ex.message)
            throw Status.INTERNAL.withDescription("Failed to fetch usage stats").asRuntimeException()
        }

        val denies = stats.totalDenies
        val total = stats.totalRequests
        val denyRate = if (total > 0) denies.toDouble() / total else 0.0

        return UsageStats.newBuilder()
            .setPolicyId(stats.policyId)
            .setTotalRequests(total)
            .setTotalAllows(stats.totalAllows)
            .setTotalDenies(denies)
            .setDenyRate(denyRate)
            .setAvgLatencyMs(stats.avgLatencyMs)
            .setTimeRange(request.timeRange)
            .build()
    }

    // -------------------------------------------------------------------------
    // StreamDecisions — live server-streaming from in-memory pipeline (RAT-15)
    // -------------------------------------------------------------------------

    override fun streamDecisions(request: com.rateforge.analytics.grpc.proto.StreamRequest): Flow<ProtoDecisionEvent> {
        log.info(
            "StreamDecisions subscriber connected (policyFilter={} keyFilter={} decisionFilter={})",
            request.policyId.ifBlank { "*" },
            request.clientKey.ifBlank { "*" },
            if (request.filterByDecision) request.decisionFilter else "ALL",
        )

        return pipeline.liveFlow
            .filter { event ->
                (request.policyId.isBlank() || event.policyId == request.policyId) &&
                (request.clientKey.isBlank() || event.clientKey == request.clientKey) &&
                (!request.filterByDecision || event.decision.toProto() == request.decisionFilter)
            }
            .map { event -> event.toProto() }
    }

    // -------------------------------------------------------------------------
    // GetTopClients — reads from PostgreSQL (RAT-15)
    // -------------------------------------------------------------------------

    override suspend fun getTopClients(request: TopClientsRequest): TopClientsList {
        val sinceMs = request.timeRange.toSinceMs()
        val limit = if (request.limit > 0) request.limit else 10

        log.debug("GetTopClients range={} limit={}", request.timeRange, limit)

        val clients = try {
            repository.getTopClients(sinceMs, limit)
        } catch (ex: Exception) {
            log.error("GetTopClients query failed: {}", ex.message)
            throw Status.INTERNAL.withDescription("Failed to fetch top clients").asRuntimeException()
        }

        val clientStats = clients.map { c ->
            val denyRatio = if (c.totalRequests > 0) c.totalDenies.toDouble() / c.totalRequests else 0.0
            ClientStats.newBuilder()
                .setClientKey(c.clientKey)
                .setTotalRequests(c.totalRequests)
                .setTotalAllows(c.totalAllows)
                .setTotalDenies(c.totalDenies)
                .setDenyRatio(denyRatio)
                .build()
        }

        return TopClientsList.newBuilder()
            .addAllClients(clientStats)
            .setTimeRange(request.timeRange)
            .build()
    }

    // -------------------------------------------------------------------------
    // Mapping helpers
    // -------------------------------------------------------------------------

    private fun TimeRange.toSinceMs(): Long {
        val now = Instant.now()
        return when (this) {
            TimeRange.ONE_HOUR    -> now.minus(1, ChronoUnit.HOURS)
            TimeRange.SIX_HOURS   -> now.minus(6, ChronoUnit.HOURS)
            TimeRange.ONE_DAY     -> now.minus(1, ChronoUnit.DAYS)
            TimeRange.SEVEN_DAYS  -> now.minus(7, ChronoUnit.DAYS)
            TimeRange.THIRTY_DAYS -> now.minus(30, ChronoUnit.DAYS)
            else                  -> now.minus(1, ChronoUnit.HOURS)
        }.toEpochMilli()
    }

    private fun com.rateforge.analytics.pipeline.DecisionEvent.toProto(): ProtoDecisionEvent =
        ProtoDecisionEvent.newBuilder()
            .setId(id)
            .setTimestampMs(timestampMs)
            .setClientKey(clientKey)
            .setEndpoint(endpoint)
            .setPolicyId(policyId)
            .setAlgorithm(algorithm.toProto())
            .setDecision(decision.toProto())
            .setLatencyMs(latencyMs)
            .build()

    private fun Algorithm.toProto(): ProtoAlgorithm = when (this) {
        Algorithm.FIXED_WINDOW   -> ProtoAlgorithm.FIXED_WINDOW
        Algorithm.SLIDING_WINDOW -> ProtoAlgorithm.SLIDING_WINDOW
        Algorithm.TOKEN_BUCKET   -> ProtoAlgorithm.TOKEN_BUCKET
    }

    private fun DecisionResult.toProto(): ProtoDecisionResult = when (this) {
        DecisionResult.ALLOW -> ProtoDecisionResult.ALLOW
        DecisionResult.DENY  -> ProtoDecisionResult.DENY
    }
}
