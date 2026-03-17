package com.rateforge.analytics.repository

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
interface DecisionEventRepository : JpaRepository<DecisionEventEntity, UUID> {

    @Query(value = """
        SELECT
            COALESCE(CAST(:policyId AS text), '')        AS policy_id,
            COUNT(*)                                      AS total_requests,
            SUM(CASE WHEN allowed     THEN 1 ELSE 0 END) AS total_allows,
            SUM(CASE WHEN NOT allowed THEN 1 ELSE 0 END) AS total_denies,
            COALESCE(AVG(latency_us) / 1000.0, 0)        AS avg_latency_ms
        FROM decision_events
        WHERE (:policyId IS NULL OR policy_id = CAST(:policyId AS uuid))
          AND (:clientId IS NULL OR client_id  = :clientId)
          AND occurred_at >= :since
        """, nativeQuery = true)
    fun getAggregatedStats(
        @Param("policyId") policyId: String?,
        @Param("clientId") clientId: String?,
        @Param("since")    since: Instant,
    ): AggregatedStatsProjection

    @Query(value = """
        SELECT client_id,
               COUNT(*)                                              AS total_requests,
               SUM(CASE WHEN allowed     THEN 1 ELSE 0 END)         AS total_allows,
               SUM(CASE WHEN NOT allowed THEN 1 ELSE 0 END)         AS total_denies
        FROM decision_events
        WHERE occurred_at >= :since
        GROUP BY client_id
        ORDER BY COUNT(*) DESC
        LIMIT :lim
        """, nativeQuery = true)
    fun getTopClients(
        @Param("since") since: Instant,
        @Param("lim")   limit: Int,
    ): List<ClientStatsProjection>

    @Query(value = """
        SELECT id, client_id, endpoint, method, policy_id,
               allowed, reason, latency_us, occurred_at
        FROM decision_events
        WHERE occurred_at > :since
          AND (:policyId   IS NULL OR policy_id = CAST(:policyId AS uuid))
          AND (:clientId   IS NULL OR client_id = :clientId)
          AND (:allowedVal IS NULL OR allowed   = :allowedVal)
        ORDER BY occurred_at ASC
        LIMIT :lim
        """, nativeQuery = true)
    fun getRecentEvents(
        @Param("since")      since: Instant,
        @Param("policyId")   policyId: String?,
        @Param("clientId")   clientId: String?,
        @Param("allowedVal") allowedOnly: Boolean?,
        @Param("lim")        limit: Int,
    ): List<DecisionEventEntity>
}

interface AggregatedStatsProjection {
    val policyId: String; val totalRequests: Long
    val totalAllows: Long; val totalDenies: Long; val avgLatencyMs: Double
}

interface ClientStatsProjection {
    val clientId: String; val totalRequests: Long
    val totalAllows: Long; val totalDenies: Long
}
