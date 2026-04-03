package com.rateforge.security

import io.grpc.*
import io.mockk.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class AuthInterceptorTest {

    private lateinit var serverCall: ServerCall<Any, Any>
    private lateinit var serverCallHandler: ServerCallHandler<Any, Any>
    private lateinit var listener: ServerCall.Listener<Any>
    private lateinit var methodDescriptor: MethodDescriptor<Any, Any>
    
    @BeforeEach
    fun setUp() {
        serverCall = mockk(relaxed = true)
        serverCallHandler = mockk()
        listener = mockk()
        methodDescriptor = mockk()
        
        every { serverCallHandler.startCall(any(), any()) } returns listener
    }
    
    @Test
    fun `should allow requests when auth is disabled`() {
        val authProperties = AuthProperties(enabled = false)
        val interceptor = AuthInterceptor(authProperties)
        
        every { serverCall.methodDescriptor } returns methodDescriptor
        every { methodDescriptor.fullMethodName } returns "com.rateforge.RateLimiterService/CheckLimit"
        
        val headers = Metadata()
        val result = interceptor.interceptCall(serverCall, headers, serverCallHandler)
        
        assertEquals(listener, result)
        verify(exactly = 0) { serverCall.close(any(), any()) }
    }
    
    @Test
    fun `should allow health check without auth`() {
        val authProperties = AuthProperties(enabled = true, apiKeys = listOf("secret-key"))
        val interceptor = AuthInterceptor(authProperties)
        
        every { serverCall.methodDescriptor } returns methodDescriptor
        every { methodDescriptor.fullMethodName } returns "grpc.health.v1.Health/Check"
        
        val headers = Metadata() // No API key
        val result = interceptor.interceptCall(serverCall, headers, serverCallHandler)
        
        assertEquals(listener, result)
        verify(exactly = 0) { serverCall.close(any(), any()) }
    }
    
    @Test
    fun `should allow reflection without auth`() {
        val authProperties = AuthProperties(enabled = true, apiKeys = listOf("secret-key"))
        val interceptor = AuthInterceptor(authProperties)
        
        every { serverCall.methodDescriptor } returns methodDescriptor
        every { methodDescriptor.fullMethodName } returns "grpc.reflection.v1alpha.ServerReflection/ServerReflectionInfo"
        
        val headers = Metadata()
        val result = interceptor.interceptCall(serverCall, headers, serverCallHandler)
        
        assertEquals(listener, result)
        verify(exactly = 0) { serverCall.close(any(), any()) }
    }
    
    @Test
    fun `should reject request without API key when auth enabled`() {
        val authProperties = AuthProperties(enabled = true, apiKeys = listOf("secret-key"))
        val interceptor = AuthInterceptor(authProperties)
        
        every { serverCall.methodDescriptor } returns methodDescriptor
        every { methodDescriptor.fullMethodName } returns "com.rateforge.RateLimiterService/CheckLimit"
        
        val headers = Metadata() // No API key
        val statusSlot = slot<Status>()
        every { serverCall.close(capture(statusSlot), any()) } just Runs
        
        interceptor.interceptCall(serverCall, headers, serverCallHandler)
        
        verify(exactly = 1) { serverCall.close(any(), any()) }
        assertEquals(Status.Code.UNAUTHENTICATED, statusSlot.captured.code)
        assertTrue(statusSlot.captured.description?.contains("Missing API key") == true)
    }
    
    @Test
    fun `should reject request with invalid API key`() {
        val authProperties = AuthProperties(enabled = true, apiKeys = listOf("valid-key"))
        val interceptor = AuthInterceptor(authProperties)
        
        every { serverCall.methodDescriptor } returns methodDescriptor
        every { methodDescriptor.fullMethodName } returns "com.rateforge.RateLimiterService/CheckLimit"
        
        val headers = Metadata().apply {
            put(Metadata.Key.of("x-api-key", Metadata.ASCII_STRING_MARSHALLER), "wrong-key")
        }
        
        val statusSlot = slot<Status>()
        every { serverCall.close(capture(statusSlot), any()) } just Runs
        
        interceptor.interceptCall(serverCall, headers, serverCallHandler)
        
        verify(exactly = 1) { serverCall.close(any(), any()) }
        assertEquals(Status.Code.UNAUTHENTICATED, statusSlot.captured.code)
        assertTrue(statusSlot.captured.description?.contains("Invalid API key") == true)
    }
    
    @Test
    fun `should allow request with valid API key`() {
        val authProperties = AuthProperties(enabled = true, apiKeys = listOf("valid-key", "another-valid-key"))
        val interceptor = AuthInterceptor(authProperties)
        
        every { serverCall.methodDescriptor } returns methodDescriptor
        every { methodDescriptor.fullMethodName } returns "com.rateforge.RateLimiterService/CheckLimit"
        
        val headers = Metadata().apply {
            put(Metadata.Key.of("x-api-key", Metadata.ASCII_STRING_MARSHALLER), "valid-key")
        }
        
        val result = interceptor.interceptCall(serverCall, headers, serverCallHandler)
        
        assertEquals(listener, result)
        verify(exactly = 0) { serverCall.close(any(), any()) }
    }
    
    @Test
    fun `should use custom header name from config`() {
        val authProperties = AuthProperties(
            enabled = true, 
            apiKeys = listOf("secret"),
            apiKeyHeader = "authorization"
        )
        val interceptor = AuthInterceptor(authProperties)
        
        every { serverCall.methodDescriptor } returns methodDescriptor
        every { methodDescriptor.fullMethodName } returns "com.rateforge.RateLimiterService/CheckLimit"
        
        val headers = Metadata().apply {
            put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), "secret")
        }
        
        val result = interceptor.interceptCall(serverCall, headers, serverCallHandler)
        
        assertEquals(listener, result)
        verify(exactly = 0) { serverCall.close(any(), any()) }
    }
}
