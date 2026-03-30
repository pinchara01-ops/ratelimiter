package com.rateforge.grpc

import io.grpc.Status
import io.grpc.StatusRuntimeException
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Utility for sanitizing error messages before returning to clients.
 * Prevents leakage of internal implementation details, stack traces,
 * database schema info, file paths, and other sensitive information.
 */
object ErrorSanitizer {
    
    private val log = LoggerFactory.getLogger(ErrorSanitizer::class.java)
    
    // Patterns that indicate sensitive information that should be scrubbed
    private val sensitivePatterns = listOf(
        Regex("(?i)password\\s*[=:]", RegexOption.IGNORE_CASE),
        Regex("(?i)secret\\s*[=:]", RegexOption.IGNORE_CASE),
        Regex("(?i)(?:auth|bearer|access|refresh)[_-]?token", RegexOption.IGNORE_CASE),
        Regex("(?i)api[_-]?key\\s*[=:]", RegexOption.IGNORE_CASE),
        Regex("(?i)credential", RegexOption.IGNORE_CASE),
        Regex("[A-Za-z]:\\\\[^\\s]+"), // Windows file paths
        Regex("/(?:home|var|etc|usr|opt)/[^\\s]+"), // Unix file paths
        Regex("at [\\w.]+\\([\\w.]+:\\d+\\)"), // Stack trace lines
        Regex("jdbc:[^\\s]+"), // JDBC URLs
        Regex("redis://[^\\s]+"), // Redis URLs
        Regex("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}"), // IP addresses
        Regex("Caused by:.*"), // Exception chains
        Regex("(?i)sql(?:state|exception)", RegexOption.IGNORE_CASE),
        Regex("\\bat\\b.*\\(.*\\.kt:\\d+\\)"), // Kotlin stack frames
        Regex("\\bat\\b.*\\(.*\\.java:\\d+\\)"), // Java stack frames
    )
    
    // Safe error messages by category
    private val safeMessages = mapOf(
        "database" to "A database error occurred. Please try again later.",
        "redis" to "A cache error occurred. Please try again later.",
        "network" to "A network error occurred. Please try again later.",
        "validation" to "Invalid request parameters.",
        "internal" to "An internal error occurred. Please try again later.",
        "timeout" to "The request timed out. Please try again.",
        "unavailable" to "The service is temporarily unavailable."
    )
    
    /**
     * Sanitize an exception message for client response.
     * Logs the full exception internally and returns a safe message.
     */
    fun sanitize(ex: Exception, context: String = "operation"): String {
        // Log full details internally
        log.error("Error in {}: {} - {}", context, ex.javaClass.simpleName, ex.message, ex)
        
        // Determine safe message category
        val category = categorizeException(ex)
        return safeMessages[category] ?: safeMessages["internal"]!!
    }
    
    /**
     * Sanitize an error message string, removing sensitive patterns.
     * Use for messages that are already somewhat safe but may contain leaks.
     */
    fun sanitizeMessage(message: String?): String {
        if (message == null) return "An error occurred."
        
        // Check if message contains sensitive patterns
        if (sensitivePatterns.any { it.containsMatchIn(message) }) {
            return safeMessages["internal"]!!
        }
        
        // Truncate long messages
        return if (message.length > 200) {
            message.take(200) + "..."
        } else {
            message
        }
    }
    
    /**
     * Create a StatusRuntimeException with a sanitized message.
     * Logs full details and returns safe exception.
     */
    fun internalError(ex: Exception, context: String): StatusRuntimeException {
        val safeMessage = sanitize(ex, context)
        return Status.INTERNAL.withDescription(safeMessage).asRuntimeException()
    }
    
    /**
     * Create a StatusRuntimeException for validation errors.
     * Validation errors are generally safe to return as-is.
     */
    fun validationError(message: String): StatusRuntimeException {
        // Validation messages should be safe but still sanitize
        val safe = sanitizeMessage(message)
        return Status.INVALID_ARGUMENT.withDescription(safe).asRuntimeException()
    }
    
    /**
     * Create a StatusRuntimeException for not found errors.
     * Avoid leaking entity IDs or internal details.
     */
    fun notFoundError(resourceType: String): StatusRuntimeException {
        return Status.NOT_FOUND.withDescription("$resourceType not found").asRuntimeException()
    }
    
    /**
     * Create a StatusRuntimeException for already exists errors.
     */
    fun alreadyExistsError(resourceType: String, identifier: String? = null): StatusRuntimeException {
        val message = if (identifier != null) {
            "$resourceType with name '$identifier' already exists"
        } else {
            "$resourceType already exists"
        }
        return Status.ALREADY_EXISTS.withDescription(message).asRuntimeException()
    }
    
    private fun categorizeException(ex: Exception): String {
        val name = ex.javaClass.simpleName.lowercase()
        val message = ex.message?.lowercase() ?: ""
        
        return when {
            name.contains("sql") || name.contains("jdbc") || 
                name.contains("database") || name.contains("hibernate") ||
                message.contains("sql") || message.contains("database") -> "database"
            
            name.contains("redis") || name.contains("jedis") ||
                message.contains("redis") -> "redis"
            
            name.contains("timeout") || message.contains("timeout") -> "timeout"
            
            name.contains("connect") || name.contains("socket") ||
                name.contains("network") || message.contains("connection") -> "network"
            
            name.contains("validation") || name.contains("constraint") ||
                name.contains("illegal") -> "validation"
            
            name.contains("unavailable") || message.contains("unavailable") -> "unavailable"
            
            else -> "internal"
        }
    }
    
    /**
     * Generate a correlation ID for error tracking.
     * This ID can be returned to clients for support reference.
     */
    fun generateCorrelationId(): String {
        return UUID.randomUUID().toString().take(8)
    }
}
