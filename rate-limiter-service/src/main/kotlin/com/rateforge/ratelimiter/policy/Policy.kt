package com.rateforge.ratelimiter.policy

import com.rateforge.ratelimiter.algorithm.AlgorithmType

/**
 * Represents a rate-limiting policy.
 *
 * @param clientKeyPattern  Optional regex. If null, matches any client key.
 * @param endpointPattern   Optional regex. If null, matches any endpoint.
 * @param priority          Higher value = matched first (RAT-10).
 * @param refillRate        Tokens per second — only relevant for TOKEN_BUCKET.
 */
data class Policy(
    val id: String,
    val name: String,
    val algorithm: AlgorithmType,
    val limit: Long,            // max requests per window (or bucket capacity)
    val windowSeconds: Long,    // window size; for TOKEN_BUCKET this is ignored
    val refillRate: Double = limit.toDouble(), // TOKEN_BUCKET: tokens/sec
    val priority: Int = 0,
    val clientKeyPattern: Regex? = null,
    val endpointPattern: Regex? = null,
)

/** Fallback policy used when no registered policy matches. */
object DefaultPolicy {
    val INSTANCE = Policy(
        id            = "default",
        name          = "Default Policy",
        algorithm     = AlgorithmType.FIXED_WINDOW,
        limit         = 1_000,
        windowSeconds = 60,
        priority      = Int.MIN_VALUE,
    )
}
