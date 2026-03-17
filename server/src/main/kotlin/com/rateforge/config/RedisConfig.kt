package com.rateforge.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.serializer.StringRedisSerializer

@Configuration
class RedisConfig {

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
