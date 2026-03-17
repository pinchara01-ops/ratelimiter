package com.rateforge.ratelimiter.config

import com.rateforge.ratelimiter.algorithm.FixedWindowAlgorithm
import com.rateforge.ratelimiter.algorithm.RateLimitAlgorithm
import com.rateforge.ratelimiter.algorithm.SlidingWindowAlgorithm
import com.rateforge.ratelimiter.algorithm.TokenBucketAlgorithm
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Wires the algorithm map injected into [RateLimitServiceImpl].
 * Key = AlgorithmType.name() so the service can look up by enum name.
 */
@Configuration
class AlgorithmConfig {

    @Bean
    fun algorithms(
        fixedWindow: FixedWindowAlgorithm,
        slidingWindow: SlidingWindowAlgorithm,
        tokenBucket: TokenBucketAlgorithm,
    ): Map<String, RateLimitAlgorithm> = mapOf(
        fixedWindow.algorithmType.name   to fixedWindow,
        slidingWindow.algorithmType.name to slidingWindow,
        tokenBucket.algorithmType.name   to tokenBucket,
    )
}
