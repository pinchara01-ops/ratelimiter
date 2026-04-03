package com.rateforge.logging

import io.grpc.Context
import io.grpc.Contexts
import io.grpc.ForwardingServerCall
import io.grpc.Metadata
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.ServerInterceptor
import net.devh.boot.grpc.server.interceptor.GrpcGlobalServerInterceptor
import org.slf4j.MDC
import java.util.UUID

/**
 * gRPC interceptor that extracts or generates correlation IDs for request tracing.
 * 
 * The correlation ID is:
 * 1. Extracted from incoming x-correlation-id header if present
 * 2. Generated as a new UUID if not present
 * 
 * The ID is propagated via:
 * - gRPC Context for downstream services
 * - SLF4J MDC for structured logging
 * - Response headers for client visibility
 */
@GrpcGlobalServerInterceptor
class CorrelationIdInterceptor : ServerInterceptor {

    companion object {
        val CORRELATION_ID_HEADER_KEY: Metadata.Key<String> = 
            Metadata.Key.of("x-correlation-id", Metadata.ASCII_STRING_MARSHALLER)
        
        val CORRELATION_ID_CONTEXT_KEY: Context.Key<String> = 
            Context.key("correlation-id")
        
        const val MDC_CORRELATION_ID = "correlationId"
        const val MDC_METHOD = "grpcMethod"
        
        /**
         * Get the current correlation ID from the gRPC context.
         */
        fun getCurrentCorrelationId(): String? = CORRELATION_ID_CONTEXT_KEY.get()
    }

    override fun <ReqT, RespT> interceptCall(
        call: ServerCall<ReqT, RespT>,
        headers: Metadata,
        next: ServerCallHandler<ReqT, RespT>
    ): ServerCall.Listener<ReqT> {
        // Extract or generate correlation ID
        val correlationId = headers.get(CORRELATION_ID_HEADER_KEY)
            ?: UUID.randomUUID().toString()

        // Create new context with correlation ID
        val context = Context.current()
            .withValue(CORRELATION_ID_CONTEXT_KEY, correlationId)

        // Extract method name for logging
        val methodName = call.methodDescriptor.fullMethodName

        // Wrap the call so the correlation ID is injected into response headers
        // when sendHeaders is called by the framework (grpc-kotlin calls it once before sendMessage).
        // Do NOT call sendHeaders eagerly here — a second call throws IllegalStateException → UNKNOWN.
        val correlatedCall = object : ForwardingServerCall.SimpleForwardingServerCall<ReqT, RespT>(call) {
            override fun sendHeaders(responseHeaders: Metadata) {
                responseHeaders.put(CORRELATION_ID_HEADER_KEY, correlationId)
                super.sendHeaders(responseHeaders)
            }
        }

        return Contexts.interceptCall(
            context,
            correlatedCall,
            headers,
            object : ServerCallHandler<ReqT, RespT> {
                override fun startCall(
                    call: ServerCall<ReqT, RespT>,
                    headers: Metadata
                ): ServerCall.Listener<ReqT> {
                    val listener = next.startCall(call, headers)
                    return MdcServerCallListener(listener, correlationId, methodName)
                }
            }
        )
    }
    
    /**
     * Listener wrapper that sets MDC context for each callback.
     */
    private class MdcServerCallListener<ReqT>(
        private val delegate: ServerCall.Listener<ReqT>,
        private val correlationId: String,
        private val methodName: String
    ) : ServerCall.Listener<ReqT>() {
        
        private inline fun <T> withMdc(block: () -> T): T {
            try {
                MDC.put(MDC_CORRELATION_ID, correlationId)
                MDC.put(MDC_METHOD, methodName)
                return block()
            } finally {
                MDC.remove(MDC_CORRELATION_ID)
                MDC.remove(MDC_METHOD)
            }
        }
        
        override fun onMessage(message: ReqT) = withMdc { delegate.onMessage(message) }
        override fun onHalfClose() = withMdc { delegate.onHalfClose() }
        override fun onCancel() = withMdc { delegate.onCancel() }
        override fun onComplete() = withMdc { delegate.onComplete() }
        override fun onReady() = withMdc { delegate.onReady() }
    }
}
