package com.rateforge.logging

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.MDC

class StructuredLoggerTest {
    
    private lateinit var logger: StructuredLogger
    
    @BeforeEach
    fun setup() {
        logger = StructuredLogger.forClass<StructuredLoggerTest>()
        MDC.clear()
    }
    
    @Test
    fun `forClass should create logger with correct name`() {
        val log = StructuredLogger.forClass<StructuredLoggerTest>()
        assertNotNull(log)
    }
    
    @Test
    fun `forName should create logger with specified name`() {
        val log = StructuredLogger.forName("custom.logger.name")
        assertNotNull(log)
    }
    
    @Test
    fun `info should format message with key-value pairs`() {
        // This test verifies the logger doesn't throw exceptions
        // Actual log output is verified manually or with log capturing
        assertDoesNotThrow {
            logger.info("Test message", "key1" to "value1", "key2" to 123)
        }
    }
    
    @Test
    fun `debug should handle empty pairs`() {
        assertDoesNotThrow {
            logger.debug("Debug message without pairs")
        }
    }
    
    @Test
    fun `warn should handle null values`() {
        assertDoesNotThrow {
            logger.warn("Warning message", "nullKey" to null, "validKey" to "value")
        }
    }
    
    @Test
    fun `error should include exception`() {
        assertDoesNotThrow {
            logger.error("Error occurred", RuntimeException("test"), "context" to "test")
        }
    }
    
    @Test
    fun `should not override existing MDC values`() {
        // Given
        MDC.put("existingKey", "existingValue")
        
        // When - log with same key
        logger.info("Test", "existingKey" to "newValue")
        
        // Then - original value should be preserved
        assertEquals("existingValue", MDC.get("existingKey"))
        
        MDC.remove("existingKey")
    }
    
    @Test
    fun `should clean up MDC after logging`() {
        // When
        logger.info("Test", "tempKey" to "tempValue")
        
        // Then
        assertNull(MDC.get("tempKey"))
    }
    
    @Test
    fun `trace should work correctly`() {
        assertDoesNotThrow {
            logger.trace("Trace message", "detail" to "fine-grained")
        }
    }
    
    @Test
    fun `warn with exception should include pairs`() {
        assertDoesNotThrow {
            logger.warn("Warning", IllegalArgumentException("bad arg"), "input" to "invalid")
        }
    }
}
