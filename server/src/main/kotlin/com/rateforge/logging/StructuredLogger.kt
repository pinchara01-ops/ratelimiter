package com.rateforge.logging

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.MDC

/**
 * Structured logging utility for consistent log formatting across the application.
 * 
 * Usage:
 * ```kotlin
 * class MyService {
 *     private val log = StructuredLogger.forClass<MyService>()
 *     
 *     fun doSomething(key: String) {
 *         log.info("Processing request", "key" to key, "action" to "process")
 *     }
 * }
 * ```
 */
class StructuredLogger(private val logger: Logger) {
    
    companion object {
        inline fun <reified T> forClass(): StructuredLogger = 
            StructuredLogger(LoggerFactory.getLogger(T::class.java))
        
        fun forName(name: String): StructuredLogger = 
            StructuredLogger(LoggerFactory.getLogger(name))
    }
    
    /**
     * Log at INFO level with structured key-value pairs.
     */
    fun info(message: String, vararg pairs: Pair<String, Any?>) {
        if (logger.isInfoEnabled) {
            withContext(pairs) {
                logger.info(formatMessage(message, pairs))
            }
        }
    }
    
    /**
     * Log at DEBUG level with structured key-value pairs.
     */
    fun debug(message: String, vararg pairs: Pair<String, Any?>) {
        if (logger.isDebugEnabled) {
            withContext(pairs) {
                logger.debug(formatMessage(message, pairs))
            }
        }
    }
    
    /**
     * Log at WARN level with structured key-value pairs.
     */
    fun warn(message: String, vararg pairs: Pair<String, Any?>) {
        if (logger.isWarnEnabled) {
            withContext(pairs) {
                logger.warn(formatMessage(message, pairs))
            }
        }
    }
    
    /**
     * Log at WARN level with exception and structured key-value pairs.
     */
    fun warn(message: String, throwable: Throwable, vararg pairs: Pair<String, Any?>) {
        if (logger.isWarnEnabled) {
            withContext(pairs) {
                logger.warn(formatMessage(message, pairs), throwable)
            }
        }
    }
    
    /**
     * Log at ERROR level with structured key-value pairs.
     */
    fun error(message: String, vararg pairs: Pair<String, Any?>) {
        if (logger.isErrorEnabled) {
            withContext(pairs) {
                logger.error(formatMessage(message, pairs))
            }
        }
    }
    
    /**
     * Log at ERROR level with exception and structured key-value pairs.
     */
    fun error(message: String, throwable: Throwable, vararg pairs: Pair<String, Any?>) {
        if (logger.isErrorEnabled) {
            withContext(pairs) {
                logger.error(formatMessage(message, pairs), throwable)
            }
        }
    }
    
    /**
     * Log at TRACE level with structured key-value pairs.
     */
    fun trace(message: String, vararg pairs: Pair<String, Any?>) {
        if (logger.isTraceEnabled) {
            withContext(pairs) {
                logger.trace(formatMessage(message, pairs))
            }
        }
    }
    
    private inline fun withContext(pairs: Array<out Pair<String, Any?>>, block: () -> Unit) {
        val keysToRemove = mutableListOf<String>()
        try {
            pairs.forEach { (key, value) ->
                if (MDC.get(key) == null) {
                    MDC.put(key, value?.toString() ?: "null")
                    keysToRemove.add(key)
                }
            }
            block()
        } finally {
            keysToRemove.forEach { MDC.remove(it) }
        }
    }
    
    private fun formatMessage(message: String, pairs: Array<out Pair<String, Any?>>): String {
        if (pairs.isEmpty()) return message
        
        val context = pairs.joinToString(", ") { (key, value) -> 
            "$key=${value ?: "null"}" 
        }
        return "$message [$context]"
    }
}
