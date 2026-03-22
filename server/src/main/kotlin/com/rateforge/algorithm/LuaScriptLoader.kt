package com.rateforge.algorithm

import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.core.io.ClassPathResource
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.scripting.support.ResourceScriptSource
import org.springframework.stereotype.Component

@Component
class LuaScriptLoader(
    private val redisTemplate: RedisTemplate<String, String>
) {
    private val log = LoggerFactory.getLogger(LuaScriptLoader::class.java)

    final lateinit var fixedWindowSha: String
        private set

    final lateinit var slidingWindowSha: String
        private set

    final lateinit var tokenBucketSha: String
        private set

    final lateinit var tokenBucketStatusSha: String
        private set

    final lateinit var fixedWindowScript: DefaultRedisScript<List<*>>
        private set

    final lateinit var slidingWindowScript: DefaultRedisScript<List<*>>
        private set

    final lateinit var tokenBucketScript: DefaultRedisScript<List<*>>
        private set

    final lateinit var tokenBucketStatusScript: DefaultRedisScript<Long>
        private set

    @PostConstruct
    fun loadScripts() {
        fixedWindowScript = buildScript("lua/fixed_window.lua")
        slidingWindowScript = buildScript("lua/sliding_window.lua")
        tokenBucketScript = buildScript("lua/token_bucket.lua")
        tokenBucketStatusScript = buildLongScript("lua/token_bucket_status.lua")

        fixedWindowSha = scriptLoad(readScript("lua/fixed_window.lua"))
        slidingWindowSha = scriptLoad(readScript("lua/sliding_window.lua"))
        tokenBucketSha = scriptLoad(readScript("lua/token_bucket.lua"))
        tokenBucketStatusSha = scriptLoad(readScript("lua/token_bucket_status.lua"))

        log.info(
            "Lua scripts loaded - fixedWindow SHA: {}, slidingWindow SHA: {}, tokenBucket SHA: {}, tokenBucketStatus SHA: {}",
            fixedWindowSha, slidingWindowSha, tokenBucketSha, tokenBucketStatusSha
        )
    }

    private fun readScript(path: String): String {
        return ClassPathResource(path).inputStream.bufferedReader().readText()
    }

    private fun buildScript(path: String): DefaultRedisScript<List<*>> {
        val script = DefaultRedisScript<List<*>>()
        script.setScriptSource(ResourceScriptSource(ClassPathResource(path)))
        @Suppress("UNCHECKED_CAST")
        script.resultType = List::class.java as Class<List<*>>
        return script
    }

    private fun buildLongScript(path: String): DefaultRedisScript<Long> {
        val script = DefaultRedisScript<Long>()
        script.setScriptSource(ResourceScriptSource(ClassPathResource(path)))
        script.resultType = Long::class.java
        return script
    }

    private fun scriptLoad(scriptContent: String): String {
        return redisTemplate.execute { connection ->
            connection.scriptingCommands().scriptLoad(scriptContent.toByteArray())
                ?: throw IllegalStateException("SCRIPT LOAD returned null")
        } ?: throw IllegalStateException("Failed to load script into Redis")
    }
}
