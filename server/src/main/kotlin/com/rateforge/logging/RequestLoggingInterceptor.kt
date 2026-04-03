package com.rateforge.logging

import io.grpc.ForwardingServerCall
import io.grpc.Metadata
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.ServerInterceptor
import io.grpc.Status
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import net.devh.boot.grpc.server.interceptor.GrpcGlobalServerInterceptor
import org.springframework.core.annotation.Order
import java.util.concurrent.TimeUnit

/**
 * Interceptor for logging all gRPC requests with timing and status information.
 * 
 * Logs include:
 * - Method name
 * - Correlation ID
 * - Request duration
 * - Response status
 * - Error details (on failure)
 */
@GrpcGlobalServerInterceptor
@Order(10) // Run after CorrelationIdInterceptor
class RequestLoggingInterceptor(
    private val meterRegistry: MeterRegistry
) : ServerInterceptor {
    
    private val log = StructuredLogger.forClass<RequestLoggingInterceptor>()
    
    override fun <ReqT, RespT> interceptCall(
        call: ServerCall<ReqT, RespT>,
        headers: Metadata,
        next: ServerCallHandler<ReqT, RespT>
    ): ServerCall.Listener<ReqT> {
        val methodName = call.methodDescriptor.fullMethodName
        val startTime = System.nanoTime()
        val correlationId = CorrelationIdInterceptor.getCurrentCorrelationId() ?: "unknown"
        
        log.debug("Request started", 
            "method" to methodName,
            "correlationId" to correlationId
        )
        
        val loggingCall = object : ForwardingServerCall.SimpleForwardingServerCall<ReqT, RespT>(call) {
            override fun close(status: Status, trailers: Metadata) {
                val durationNanos = System.nanoTime() - startTime
                val durationMs = TimeUnit.NANOSECONDS.toMillis(durationNanos)
                
                // Record metrics
                Timer.builder("grpc.server.requests")
                    .tag("method", methodName)
                    .tag("status", status.code.name)
                    .register(meterRegistry)
                    .record(durationNanos, TimeUnit.NANOSECONDS)
                
                if (status.isOk) {
                    log.info("Request completed",
                        "method" to methodName,
                        "correlationId" to correlationId,
                        "durationMs" to durationMs,
                        "status" to "OK"
                    )
                } else {
                    log.warn("Request failed",
                        "method" to methodName,
                        "correlationId" to correlationId,
                        "durationMs" to durationMs,
                        "status" to status.code.name,
                        "description" to (status.description ?: "none")
                    )
                }
                
                super.close(status, trailers)
            }
        }
        
        return next.startCall(loggingCall, headers)
    }
}
