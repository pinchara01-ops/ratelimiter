package com.rateforge.config

import org.springframework.boot.autoconfigure.data.redis.RedisProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.serializer.StringRedisSerializer
import java.time.Duration

@Configuration
class RedisConfig(
    private val redisProperties: RedisProperties,
    private val rateForgeProperties: RateForgeProperties
) {

    @Bean
    fun lettuceConnectionFactory(): LettuceConnectionFactory {
        val standaloneConfig = RedisStandaloneConfiguration(
            redisProperties.host,
            redisProperties.port
        )
        redisProperties.password?.takeIf { it.isNotBlank() }?.let {
            standaloneConfig.setPassword(it)
        }

        val clientConfig = LettuceClientConfiguration.builder()
            .commandTimeout(Duration.ofMillis(rateForgeProperties.timeouts.redisCommandMs))
            .shutdownTimeout(Duration.ofSeconds(2))
            .build()

        return LettuceConnectionFactory(standaloneConfig, clientConfig)
    }

    @Bean
    fun redisTemplate(connectionFactory: RedisConnectionFactory): RedisTemplate<String, String> {
        val template = RedisTemplate<String, String>()
        template.connectionFactory = connectionFactory
        val stringSerializer = StringRedisSerializer()
        template.keySerializer = stringSerializer
        template.valueSerializer = stringSerializer
        template.hashKeySerializer = stringSerializer
        template.hashValueSerializer = stringSerializer
        return template
    }
}
