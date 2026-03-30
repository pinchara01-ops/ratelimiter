package com.rateforge

import com.rateforge.config.RateForgeProperties
import com.rateforge.security.AuthProperties
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableConfigurationProperties(RateForgeProperties::class, AuthProperties::class)
@EnableScheduling
class RateForgeApplication

fun main(args: Array<String>) {
    runApplication<RateForgeApplication>(*args)
}
