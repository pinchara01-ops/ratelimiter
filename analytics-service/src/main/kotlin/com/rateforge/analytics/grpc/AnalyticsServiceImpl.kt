package com.rateforge.analytics.grpc

import com.rateforge.analytics.grpc.proto.*
import com.rateforge.analytics.repository.DecisionEventEntity
import com.rateforge.analytics.repository.DecisionEventRepository
import io.grpc.Status
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import net.devh.boot.grpc.server.service.GrpcService
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.temporal.ChronoUnit

@GrpcService
class AnalyticsServiceImpl(
    private val repository: DecisionEventRepository,
) : AnalyticsServiceGrpcKt.AnalyticsServiceCoroutineImplBase() {

    private val log = LoggerFactory.getLogger(AnalyticsServiceImpl::class.java)

    override suspend fun getUsageStats(request: UsageStatsRequest): UsageStats {
        val since    = request.timeRange.toSince()
        val policyId = request.policyId.takeIf { it.isNotBlank() }
        val clientId = request.clientId.takeIf { it.isNotBlank() }
        val stats = try {
            repository.getAggregatedStats(policyId, clientId, since)
        } catch (ex: Exception) {
            log.error("GetUsageStats query failed", ex)
            throw Status.INTERNAL.withDescription("Failed to fetch usage stats").asRuntimeException()
        }
        val total = stats.totalRequests; val denies = stats.totalDenies
        return UsageStats.newBuilder()
            .setPolicyId(stats.policyId).setTotalRequests(total)
            .setTotalAllows(stats.totalAllows).setTotalDenies(denies)
            .setDenyRate(if (total > 0) denies.toDouble() / total else 0.0)
            .setAvgLatencyMs(stats.avgLatencyMs).setTimeRange(request.timeRange).build()
    }

    // DB-poll streaming — server/ writes events via AnalyticsPipeline;
    // analytics-service tails the same PostgreSQL table every 500 ms.
    override fun streamDecisions(request: StreamRequest): Flow<DecisionEvent> = flow {
        val policyId   = request.policyId.takeIf { it.isNotBlank() }
        val clientId   = request.clientId.takeIf { it.isNotBlank() }
        val allowedVal = if (request.filterByAllowed) request.allowedOnly else null
        var cursor = Instant.now()
        while (true) {
            val batch = try {
                repository.getRecentEvents(cursor, policyId, clientId, allowedVal, 100)
            } catch (ex: Exception) { log.warn("poll error: {}", ex.message); delay(500); continue }
            batch.forEach { emit(it.toProto()) }
            if (batch.isNotEmpty()) cursor = batch.last().occurredAt
            delay(500)
        }
    }

    override suspend fun getTopClients(request: TopClientsRequest): TopClientsList {
        val since = request.timeRange.toSince(); val limit = if (request.limit > 0) request.limit else 10
        val clients = try {
            repository.getTopClients(since, limit)
        } catch (ex: Exception) {
            log.error("GetTopClients query failed", ex)
            throw Status.INTERNAL.withDescription("Failed to fetch top clients").asRuntimeException()
        }
        return TopClientsList.newBuilder()
            .addAllClients(clients.map { c ->                val total = c.totalRequests
                ClientStats.newBuilder().setClientId(c.clientId).setTotalRequests(total)
                    .setTotalAllows(c.totalAllows).setTotalDenies(c.totalDenies)
                    .setDenyRatio(if (total > 0) c.totalDenies.toDouble() / total else 0.0).build()
            }).setTimeRange(request.timeRange).build()
    }

    private fun TimeRange.toSince(): Instant = Instant.now().minus(
        when (this) {
            TimeRange.ONE_HOUR -> 1L; TimeRange.SIX_HOURS -> 6L; TimeRange.ONE_DAY -> 24L
            TimeRange.SEVEN_DAYS -> 168L; TimeRange.THIRTY_DAYS -> 720L; else -> 1L
        }, ChronoUnit.HOURS
    )

    private fun DecisionEventEntity.toProto(): DecisionEvent =
        DecisionEvent.newBuilder().setId(id.toString()).setOccurredAtMs(occurredAt.toEpochMilli())
            .setClientId(clientId).setEndpoint(endpoint).setMethod(method ?: "")
            .setPolicyId(policyId?.toString() ?: "").setAllowed(allowed).setReason(reason)
            .setLatencyMs((latencyUs ?: 0L) / 1000.0).build()
}
