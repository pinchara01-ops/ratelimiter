package com.rateforge.analytics.repository

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * Read-only JPA projection of the decision_events table.
 * Write side lives in server/ (AnalyticsPipeline → BatchProcessor → PostgreSQL).
 * Schema matches server/src/main/resources/db/migration/V1__create_policies.sql
 */
@Entity
@Table(
    name = "decision_events",
    indexes = [Index(name = "idx_decision_events_occurred_at", columnList = "occurred_at")]
)
data class DecisionEventEntity(
    @Id val id: UUID,
    @Column(name = "client_id",  nullable = false) val clientId: String,
    @Column(name = "endpoint",   nullable = false) val endpoint: String,
    @Column(name = "method")                       val method: String?,
    @Column(name = "policy_id")                    val policyId: UUID?,
    @Column(name = "allowed",    nullable = false) val allowed: Boolean,
    @Column(name = "reason",     nullable = false) val reason: String,
    @Column(name = "latency_us")                   val latencyUs: Long?,
    @Column(name = "occurred_at",nullable = false) val occurredAt: Instant,
)
