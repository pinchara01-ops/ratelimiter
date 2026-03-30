package com.rateforge.security

import io.grpc.*
import net.devh.boot.grpc.server.interceptor.GrpcGlobalServerInterceptor
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * gRPC server interceptor that enforces API key authentication.
 * 
 * When auth is enabled, clients must include a valid API key in the configured header.
 * Unauthenticated requests receive UNAUTHENTICATED status.
 * 
 * Health check endpoints are always allowed without authentication.
 */
@Component
@GrpcGlobalServerInterceptor
class AuthInterceptor(
    private val authProperties: AuthProperties
) : ServerInterceptor {

    private val log = LoggerFactory.getLogger(AuthInterceptor::class.java)
    
    companion object {
        // Methods that are always allowed without authentication
        private val UNAUTHENTICATED_METHODS = setOf(
            "grpc.health.v1.Health/Check",
            "grpc.health.v1.Health/Watch",
            "grpc.reflection.v1alpha.ServerReflection/ServerReflectionInfo"
        )
    }

    override fun <ReqT, RespT> interceptCall(
        call: ServerCall<ReqT, RespT>,
        headers: Metadata,
        next: ServerCallHandler<ReqT, RespT>
    ): ServerCall.Listener<ReqT> {
        val methodName = call.methodDescriptor.fullMethodName
        
        // Skip auth for health checks and reflection
        if (methodName in UNAUTHENTICATED_METHODS) {
            return next.startCall(call, headers)
        }
        
        // Skip auth if disabled
        if (!authProperties.enabled) {
            return next.startCall(call, headers)
        }
        
        // Validate API key
        val apiKey = headers.get(Metadata.Key.of(authProperties.apiKeyHeader, Metadata.ASCII_STRING_MARSHALLER))
        
        if (apiKey == null) {
            log.warn("Unauthenticated request to {}: missing API key header '{}'", methodName, authProperties.apiKeyHeader)
            call.close(
                Status.UNAUTHENTICATED.withDescription("Missing API key. Include '${authProperties.apiKeyHeader}' header."),
                Metadata()
            )
            return object : ServerCall.Listener<ReqT>() {}
        }
        
        if (apiKey !in authProperties.apiKeys) {
            log.warn("Unauthenticated request to {}: invalid API key", methodName)
            call.close(
                Status.UNAUTHENTICATED.withDescription("Invalid API key."),
                Metadata()
            )
            return object : ServerCall.Listener<ReqT>() {}
        }
        
        // API key is valid - proceed with the call
        log.debug("Authenticated request to {} with valid API key", methodName)
        return next.startCall(call, headers)
    }
}
