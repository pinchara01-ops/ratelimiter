package com.rateforge.algorithm

data class RateLimitResult(
    val allowed: Boolean,
    val remaining: Long,
    val resetAtMs: Long
)
