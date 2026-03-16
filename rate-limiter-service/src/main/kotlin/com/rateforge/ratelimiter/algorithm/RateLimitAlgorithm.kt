package com.rateforge.ratelimiter.algorithm

import com.rateforge.ratelimiter.policy.Policy

/**
 * Common contract for all rate-limiting algorithms.
 * Each implementation handles its own Redis Lua script execution (RAT-6).
 */
interface RateLimitAlgorithm {
    val algorithmType: AlgorithmType

    /** Atomic check-and-increment. Returns result without side-effects on denial. */
    fun check(redisKey: String, policy: Policy): CheckResult

    /** Read-only status query — does NOT consume a slot. */
    fun status(redisKey: String, policy: Policy): StatusResult
}

enum class AlgorithmType { FIXED_WINDOW, SLIDING_WINDOW, TOKEN_BUCKET }

data class CheckResult(
    val allowed: Boolean,
    val remaining: Long,
    val resetMs: Long,       // ms until window/bucket resets
    val algorithm: AlgorithmType,
)

data class StatusResult(
    val currentCount: Long,
    val remaining: Long,
    val fillRatio: Double,   // 0.0–1.0
    val resetMs: Long,
)
