package com.rateforge.analytics.repository

import com.rateforge.analytics.pipeline.Algorithm
import com.rateforge.analytics.pipeline.DecisionResult
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(
    name = "decision_events",
    indexes = [
        Index(name = "idx_decision_events_policy_id", columnList = "policy_id"),
        Index(name = "idx_decision_events_client_key", columnList = "client_key"),
        Index(name = "idx_decision_events_timestamp", columnList = "timestamp_ms"),
    ]
)
data class DecisionEventEntity(
    @Id
    val id: String,

    @Column(name = "timestamp_ms", nullable = false)
    val timestampMs: Long,

    @Column(name = "client_key", nullable = false)
    val clientKey: String,

    @Column(name = "endpoint", nullable = false)
    val endpoint: String,

    @Column(name = "policy_id", nullable = false)
    val policyId: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "algorithm", nullable = false)
    val algorithm: Algorithm,

    @Enumerated(EnumType.STRING)
    @Column(name = "decision", nullable = false)
    val decision: DecisionResult,

    @Column(name = "latency_ms", nullable = false)
    val latencyMs: Double,
)
