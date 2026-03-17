package com.rateforge.config.entity

import jakarta.persistence.*
import java.time.Instant

@Entity
@Table(name = "policies")
class PolicyEntity(
    @Id
    @Column(name = "id", nullable = false)
    val id: String,

    @Column(name = "policy_limit", nullable = false)
    var limit: Long,

    @Column(name = "window_seconds", nullable = false)
    var windowSeconds: Long,

    @Column(name = "algorithm", nullable = false)
    var algorithm: String,   // FIXED_WINDOW | SLIDING_WINDOW | TOKEN_BUCKET

    @Column(name = "priority", nullable = false)
    var priority: Int,

    @Column(name = "client_key_pattern")
    var clientKeyPattern: String? = null,

    @Column(name = "endpoint_pattern")
    var endpointPattern: String? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
)
