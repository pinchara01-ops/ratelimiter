package com.rateforge.analytics.repository

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface DecisionEventRepository : JpaRepository<DecisionEventEntity, String> {

    // Native query: JPQL IS NULL checks on parameters are unreliable in Hibernate 6.x
    @Query(
        value = """
        SELECT
            COALESCE(:policyId, '')  AS policy_id,
            COUNT(*)                 AS total_requests,
            SUM(CASE WHEN decision = 'ALLOW' THEN 1 ELSE 0 END) AS total_allows,
            SUM(CASE WHEN decision = 'DENY'  THEN 1 ELSE 0 END) AS total_denies,
            AVG(latency_ms)          AS avg_latency_ms
        FROM decision_events
        WHERE (:policyId IS NULL OR policy_id  = :policyId)
          AND (:clientKey IS NULL OR client_key = :clientKey)
          AND timestamp_ms >= :sinceMs
        """,
        nativeQuery = true
    )
    fun getAggregatedStats(
        @Param("policyId") policyId: String?,
        @Param("clientKey") clientKey: String?,
        @Param("sinceMs") sinceMs: Long,
    ): AggregatedStatsProjection

    // Native query: LIMIT is not valid JPQL syntax — must use nativeQuery = true
    @Query(
        value = """
        SELECT client_key,
               COUNT(*)                                              AS total_requests,
               SUM(CASE WHEN decision = 'ALLOW' THEN 1 ELSE 0 END) AS total_allows,
               SUM(CASE WHEN decision = 'DENY'  THEN 1 ELSE 0 END) AS total_denies
        FROM decision_events
        WHERE timestamp_ms >= :sinceMs
        GROUP BY client_key
        ORDER BY COUNT(*) DESC
        LIMIT :limit
        """,
        nativeQuery = true
    )
    fun getTopClients(
        @Param("sinceMs") sinceMs: Long,
        @Param("limit") limit: Int,
    ): List<ClientStatsProjection>
}

interface AggregatedStatsProjection {
    val policyId: String
    val totalRequests: Long
    val totalAllows: Long
    val totalDenies: Long
    val avgLatencyMs: Double
}

interface ClientStatsProjection {
    val clientKey: String
    val totalRequests: Long
    val totalAllows: Long
    val totalDenies: Long
}
