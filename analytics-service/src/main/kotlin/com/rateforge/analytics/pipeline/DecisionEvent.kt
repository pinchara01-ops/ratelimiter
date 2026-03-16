package com.rateforge.analytics.pipeline

import java.time.Instant
import java.util.UUID

enum class Algorithm { FIXED_WINDOW, SLIDING_WINDOW, TOKEN_BUCKET }

enum class DecisionResult { ALLOW, DENY }

data class DecisionEvent(
    val id: String = UUID.randomUUID().toString(),
    val timestampMs: Long = Instant.now().toEpochMilli(),
    val clientKey: String,
    val endpoint: String,
    val policyId: String,
    val algorithm: Algorithm,
    val decision: DecisionResult,
    val latencyMs: Double,
)
