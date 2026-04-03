package com.rateforge.config

import io.lettuce.core.resource.ClientResources
import io.lettuce.core.resource.DefaultClientResources
import org.apache.commons.pool2.impl.GenericObjectPoolConfig
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.data.redis.RedisProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.connection.lettuce.LettucePoolingClientConfiguration
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.serializer.StringRedisSerializer
import java.time.Duration

@Configuration
class RedisConfig(
    private val redisProperties: RedisProperties,
    private val rateForgeProperties: RateForgeProperties
) {
    private val log = LoggerFactory.getLogger(RedisConfig::class.java)

    @Bean(destroyMethod = "shutdown")
    fun lettuceClientResources(): ClientResources {
        return DefaultClientResources.builder()
            .ioThreadPoolSize(rateForgeProperties.redis.ioThreads)
            .computationThreadPoolSize(rateForgeProperties.redis.computationThreads)
            .build()
    }

    @Bean
    fun lettuceConnectionFactory(clientResources: ClientResources): LettuceConnectionFactory {
        val standaloneConfig = RedisStandaloneConfiguration(
            redisProperties.host,
            redisProperties.port
        )
        redisProperties.password?.takeIf { it.isNotBlank() }?.let {
            standaloneConfig.setPassword(it)
        }

        val poolConfig = rateForgeProperties.redis.pool
        log.info("Configuring Redis connection pool: minIdle={}, maxIdle={}, maxTotal={}", 
            poolConfig.minIdle, poolConfig.maxIdle, poolConfig.maxTotal)

        val clientConfig = LettucePoolingClientConfiguration.builder()
            .commandTimeout(Duration.ofMillis(rateForgeProperties.timeouts.redisCommandMs))
            .shutdownTimeout(Duration.ofSeconds(2))
            .clientResources(clientResources)
            .poolConfig(buildPoolConfig(poolConfig))
            .build()

        return LettuceConnectionFactory(standaloneConfig, clientConfig)
    }

    private fun buildPoolConfig(poolProps: RedisPoolProperties): GenericObjectPoolConfig<Any> {
        return GenericObjectPoolConfig<Any>().apply {
            minIdle = poolProps.minIdle
            maxIdle = poolProps.maxIdle
            maxTotal = poolProps.maxTotal
            setMaxWait(Duration.ofMillis(poolProps.maxWaitMs))
            testOnBorrow = poolProps.testOnBorrow
            testOnReturn = poolProps.testOnReturn
            testWhileIdle = poolProps.testWhileIdle
            timeBetweenEvictionRuns = Duration.ofMillis(poolProps.timeBetweenEvictionRunsMs)
            minEvictableIdleTime = Duration.ofMillis(poolProps.minEvictableIdleTimeMs)
            blockWhenExhausted = true
        }
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
