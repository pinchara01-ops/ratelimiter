package com.rateforge

import com.rateforge.config.RateForgeProperties
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableConfigurationProperties(RateForgeProperties::class)
@EnableScheduling
class RateForgeApplication

fun main(args: Array<String>) {
    runApplication<RateForgeApplication>(*args)
}
