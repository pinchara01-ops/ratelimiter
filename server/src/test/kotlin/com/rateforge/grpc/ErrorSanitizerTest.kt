package com.rateforge.grpc

import io.grpc.Status
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class ErrorSanitizerTest {
    
    @Test
    fun `sanitizeMessage should pass through safe messages`() {
        val safeMessage = "Invalid request parameters"
        assertEquals(safeMessage, ErrorSanitizer.sanitizeMessage(safeMessage))
    }
    
    @Test
    fun `sanitizeMessage should scrub messages containing passwords`() {
        val sensitiveMessage = "Connection failed: password=secret123"
        val result = ErrorSanitizer.sanitizeMessage(sensitiveMessage)
        assertFalse(result.contains("secret123"))
        assertEquals("An internal error occurred. Please try again later.", result)
    }
    
    @Test
    fun `sanitizeMessage should allow token bucket in validation messages`() {
        val message = "Token bucket requires bucketSize > 0"
        val result = ErrorSanitizer.sanitizeMessage(message)
        assertEquals(message, result)
    }
    
    @Test
    fun `sanitizeMessage should scrub file paths`() {
        val messageWithPath = "Error loading config from C:\\Users\\admin\\config.yml"
        val result = ErrorSanitizer.sanitizeMessage(messageWithPath)
        assertFalse(result.contains("Users"))
        assertFalse(result.contains("admin"))
    }
    
    @Test
    fun `sanitizeMessage should scrub Unix paths`() {
        val messageWithPath = "Failed to read /home/user/.secrets/api-key"
        val result = ErrorSanitizer.sanitizeMessage(messageWithPath)
        assertFalse(result.contains("/home"))
        assertFalse(result.contains("secrets"))
    }
    
    @Test
    fun `sanitizeMessage should scrub stack traces`() {
        val messageWithStackTrace = "Error at com.rateforge.Service.doThing(Service.kt:42)"
        val result = ErrorSanitizer.sanitizeMessage(messageWithStackTrace)
        assertFalse(result.contains("Service.kt"))
        assertFalse(result.contains(":42"))
    }
    
    @Test
    fun `sanitizeMessage should scrub JDBC URLs`() {
        val messageWithJdbc = "Connection failed: jdbc:postgresql://localhost:5432/mydb"
        val result = ErrorSanitizer.sanitizeMessage(messageWithJdbc)
        assertFalse(result.contains("jdbc:"))
        assertFalse(result.contains("postgresql"))
    }
    
    @Test
    fun `sanitizeMessage should scrub IP addresses`() {
        val messageWithIP = "Cannot connect to 192.168.1.100"
        val result = ErrorSanitizer.sanitizeMessage(messageWithIP)
        assertFalse(result.contains("192.168"))
    }
    
    @Test
    fun `sanitizeMessage should truncate long messages`() {
        val longMessage = "x".repeat(300)
        val result = ErrorSanitizer.sanitizeMessage(longMessage)
        assertEquals(203, result.length) // 200 + "..."
        assertTrue(result.endsWith("..."))
    }
    
    @Test
    fun `sanitizeMessage should handle null`() {
        assertEquals("An error occurred.", ErrorSanitizer.sanitizeMessage(null))
    }
    
    @Test
    fun `validationError should return INVALID_ARGUMENT status`() {
        val error = ErrorSanitizer.validationError("Field is required")
        assertEquals(Status.INVALID_ARGUMENT.code, error.status.code)
        assertTrue(error.status.description?.contains("Field is required") == true)
    }
    
    @Test
    fun `notFoundError should return NOT_FOUND status`() {
        val error = ErrorSanitizer.notFoundError("Policy")
        assertEquals(Status.NOT_FOUND.code, error.status.code)
        assertEquals("Policy not found", error.status.description)
    }
    
    @Test
    fun `alreadyExistsError should return ALREADY_EXISTS status`() {
        val error = ErrorSanitizer.alreadyExistsError("Policy", "my-policy")
        assertEquals(Status.ALREADY_EXISTS.code, error.status.code)
        assertTrue(error.status.description?.contains("my-policy") == true)
    }
    
    @Test
    fun `internalError should return INTERNAL status with safe message`() {
        val ex = RuntimeException("SQL Error: connection to database failed at jdbc:postgresql://prod.db:5432")
        val error = ErrorSanitizer.internalError(ex, "testOperation")
        
        assertEquals(Status.INTERNAL.code, error.status.code)
        // Should not contain sensitive details
        assertFalse(error.status.description?.contains("jdbc:") == true)
        assertFalse(error.status.description?.contains("prod.db") == true)
    }
    
    @Test
    fun `generateCorrelationId should return 8 character string`() {
        val correlationId = ErrorSanitizer.generateCorrelationId()
        assertEquals(8, correlationId.length)
    }
}
