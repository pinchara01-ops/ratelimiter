package com.rateforge.logging

import io.grpc.Metadata
import io.grpc.MethodDescriptor
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.Status
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.MDC

class CorrelationIdInterceptorTest {
    
    private lateinit var interceptor: CorrelationIdInterceptor
    private lateinit var mockCall: ServerCall<String, String>
    private lateinit var mockHandler: ServerCallHandler<String, String>
    private lateinit var mockListener: ServerCall.Listener<String>
    
    @BeforeEach
    fun setup() {
        interceptor = CorrelationIdInterceptor()
        mockCall = mockk(relaxed = true)
        mockHandler = mockk()
        mockListener = mockk(relaxed = true)
        
        val methodDescriptor = mockk<MethodDescriptor<String, String>>()
        every { methodDescriptor.fullMethodName } returns "test.Service/TestMethod"
        every { mockCall.methodDescriptor } returns methodDescriptor
        every { mockHandler.startCall(any(), any()) } returns mockListener
    }
    
    @Test
    fun `should extract correlation ID from header`() {
        // Given
        val headers = Metadata()
        val expectedCorrelationId = "test-correlation-123"
        headers.put(CorrelationIdInterceptor.CORRELATION_ID_HEADER_KEY, expectedCorrelationId)
        
        val headersSlot = slot<Metadata>()
        every { mockCall.sendHeaders(capture(headersSlot)) } answers {}
        
        // When
        interceptor.interceptCall(mockCall, headers, mockHandler)
        
        // Then
        val responseHeaders = headersSlot.captured
        assertEquals(expectedCorrelationId, responseHeaders.get(CorrelationIdInterceptor.CORRELATION_ID_HEADER_KEY))
    }
    
    @Test
    fun `should generate correlation ID when not present`() {
        // Given
        val headers = Metadata() // No correlation ID header
        
        val headersSlot = slot<Metadata>()
        every { mockCall.sendHeaders(capture(headersSlot)) } answers {}
        
        // When
        interceptor.interceptCall(mockCall, headers, mockHandler)
        
        // Then
        val responseHeaders = headersSlot.captured
        val generatedId = responseHeaders.get(CorrelationIdInterceptor.CORRELATION_ID_HEADER_KEY)
        assertNotNull(generatedId)
        assertTrue(generatedId!!.matches(Regex("[a-f0-9-]{36}")), "Should be a valid UUID")
    }
    
    @Test
    fun `should set MDC values during onMessage`() {
        // Given
        val headers = Metadata()
        val correlationId = "test-mdc-correlation"
        headers.put(CorrelationIdInterceptor.CORRELATION_ID_HEADER_KEY, correlationId)
        
        var capturedCorrelationId: String? = null
        var capturedMethod: String? = null
        
        every { mockListener.onMessage(any()) } answers {
            capturedCorrelationId = MDC.get(CorrelationIdInterceptor.MDC_CORRELATION_ID)
            capturedMethod = MDC.get(CorrelationIdInterceptor.MDC_METHOD)
        }
        
        // When
        val listener = interceptor.interceptCall(mockCall, headers, mockHandler)
        listener.onMessage("test message")
        
        // Then
        assertEquals(correlationId, capturedCorrelationId)
        assertEquals("test.Service/TestMethod", capturedMethod)
        
        // MDC should be cleared after
        assertNull(MDC.get(CorrelationIdInterceptor.MDC_CORRELATION_ID))
        assertNull(MDC.get(CorrelationIdInterceptor.MDC_METHOD))
    }
    
    @Test
    fun `should clear MDC even if exception occurs`() {
        // Given
        val headers = Metadata()
        headers.put(CorrelationIdInterceptor.CORRELATION_ID_HEADER_KEY, "test-exception")
        
        every { mockListener.onMessage(any()) } throws RuntimeException("Test exception")
        
        // When
        val listener = interceptor.interceptCall(mockCall, headers, mockHandler)
        
        try {
            listener.onMessage("test")
        } catch (_: RuntimeException) {
            // Expected
        }
        
        // Then - MDC should still be cleared
        assertNull(MDC.get(CorrelationIdInterceptor.MDC_CORRELATION_ID))
        assertNull(MDC.get(CorrelationIdInterceptor.MDC_METHOD))
    }
    
    @Test
    fun `should call all listener methods with MDC context`() {
        // Given
        val headers = Metadata()
        headers.put(CorrelationIdInterceptor.CORRELATION_ID_HEADER_KEY, "test-all-methods")
        
        val correlationIds = mutableListOf<String?>()
        
        every { mockListener.onHalfClose() } answers { 
            correlationIds.add(MDC.get(CorrelationIdInterceptor.MDC_CORRELATION_ID))
        }
        every { mockListener.onCancel() } answers { 
            correlationIds.add(MDC.get(CorrelationIdInterceptor.MDC_CORRELATION_ID))
        }
        every { mockListener.onComplete() } answers { 
            correlationIds.add(MDC.get(CorrelationIdInterceptor.MDC_CORRELATION_ID))
        }
        every { mockListener.onReady() } answers { 
            correlationIds.add(MDC.get(CorrelationIdInterceptor.MDC_CORRELATION_ID))
        }
        
        // When
        val listener = interceptor.interceptCall(mockCall, headers, mockHandler)
        listener.onHalfClose()
        listener.onCancel()
        listener.onComplete()
        listener.onReady()
        
        // Then
        assertEquals(4, correlationIds.size)
        assertTrue(correlationIds.all { it == "test-all-methods" })
    }
}
