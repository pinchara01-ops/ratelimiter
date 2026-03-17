package com.rateforge.analytics

import java.time.Instant
import java.util.UUID

data class DecisionEvent(
    val id: UUID = UUID.randomUUID(),
    val clientId: String,
    val endpoint: String,
    val method: String?,
    val policyId: UUID?,
    val allowed: Boolean,
    val reason: String,
    val latencyUs: Long?,
    val occurredAt: Instant = Instant.now()
)

object DecisionReason {
    const val ALLOWED = "allowed"
    const val RATE_LIMITED = "rate_limited"
    const val NO_POLICY_FAIL_OPEN = "no_policy_fail_open"
    const val NO_POLICY_FAIL_CLOSED = "no_policy_fail_closed"
    const val CIRCUIT_OPEN = "circuit_open"
    const val ERROR_FAIL_OPEN = "error_fail_open"
}
