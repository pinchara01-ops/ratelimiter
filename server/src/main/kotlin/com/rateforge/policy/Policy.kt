package com.rateforge.policy

import com.rateforge.algorithm.AlgorithmType
import jakarta.persistence.*
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import java.time.Instant
import java.util.UUID

enum class NoMatchBehavior {
    FAIL_OPEN, FAIL_CLOSED
}

@Entity
@Table(name = "policies")
class PolicyEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID = UUID.randomUUID(),

    @Column(nullable = false, unique = true)
    var name: String,

    @Column(name = "client_id", nullable = false)
    var clientId: String = "*",

    @Column(nullable = false)
    var endpoint: String = "*",

    @Column(nullable = false)
    var method: String = "*",

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    var algorithm: AlgorithmType,

    @Column(name = "\"limit\"", nullable = false)
    var limit: Long,

    @Column(name = "window_ms", nullable = false)
    var windowMs: Long,

    @Column(name = "bucket_size")
    var bucketSize: Long? = null,

    @Column(name = "refill_rate")
    var refillRate: Double? = null,

    @Column(nullable = false)
    var cost: Long = 1L,

    @Column(nullable = false)
    var priority: Int = 100,

    @Column(name = "no_match_behavior")
    @Enumerated(EnumType.STRING)
    var noMatchBehavior: NoMatchBehavior? = null,

    @Column(nullable = false)
    var enabled: Boolean = true,

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now(),

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()
)

data class Policy(
    val id: UUID,
    val name: String,
    val clientId: String,
    val endpoint: String,
    val method: String,
    val algorithm: AlgorithmType,
    val limit: Long,
    val windowMs: Long,
    val bucketSize: Long?,
    val refillRate: Double?,
    val cost: Long = 1,
    val priority: Int,
    val noMatchBehavior: NoMatchBehavior? = null,
    val enabled: Boolean = true,
    val createdAt: Instant,
    val updatedAt: Instant
)

fun PolicyEntity.toDomain(): Policy = Policy(
    id = id,
    name = name,
    clientId = clientId,
    endpoint = endpoint,
    method = method,
    algorithm = algorithm,
    limit = limit,
    windowMs = windowMs,
    bucketSize = bucketSize,
    refillRate = refillRate,
    cost = cost,
    priority = priority,
    noMatchBehavior = noMatchBehavior,
    enabled = enabled,
    createdAt = createdAt,
    updatedAt = updatedAt
)
