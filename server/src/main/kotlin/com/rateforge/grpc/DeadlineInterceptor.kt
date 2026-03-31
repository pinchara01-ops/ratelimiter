package com.rateforge.grpc

import com.rateforge.config.RateForgeProperties
import io.grpc.*
import net.devh.boot.grpc.server.interceptor.GrpcGlobalServerInterceptor
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * gRPC interceptor that enforces request deadlines.
 * 
 * If a client doesn't set a deadline, this interceptor adds a default deadline
 * based on the configured timeout. This prevents runaway requests from consuming
 * server resources indefinitely.
 */
@GrpcGlobalServerInterceptor
class DeadlineInterceptor(
    private val properties: RateForgeProperties
) : ServerInterceptor {

    private val log = LoggerFactory.getLogger(DeadlineInterceptor::class.java)
    
    // Shared executor for deadline scheduling
    private val deadlineExecutor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "grpc-deadline").apply { isDaemon = true }
    }

    override fun <ReqT, RespT> interceptCall(
        call: ServerCall<ReqT, RespT>,
        headers: Metadata,
        next: ServerCallHandler<ReqT, RespT>
    ): ServerCall.Listener<ReqT> {
        val timeoutMs = properties.timeouts.grpcRequestMs
        
        // If timeout is 0, don't enforce a deadline
        if (timeoutMs <= 0) {
            return next.startCall(call, headers)
        }
        
        val existingDeadline = Context.current().deadline
        
        // If client already set a deadline, respect it (but cap at our max)
        if (existingDeadline != null) {
            val remainingMs = existingDeadline.timeRemaining(TimeUnit.MILLISECONDS)
            if (remainingMs > timeoutMs) {
                // Client deadline is longer than our max, use ours
                return wrapWithDeadline(call, headers, next, timeoutMs)
            }
            // Client deadline is shorter or equal, let it proceed
            return next.startCall(call, headers)
        }
        
        // No client deadline, add our default
        return wrapWithDeadline(call, headers, next, timeoutMs)
    }

    private fun <ReqT, RespT> wrapWithDeadline(
        call: ServerCall<ReqT, RespT>,
        headers: Metadata,
        next: ServerCallHandler<ReqT, RespT>,
        timeoutMs: Long
    ): ServerCall.Listener<ReqT> {
        val deadline = Deadline.after(timeoutMs, TimeUnit.MILLISECONDS)
        val newContext = Context.current().withDeadline(deadline, deadlineExecutor)
        
        return Contexts.interceptCall(newContext, call, headers, next)
    }
}
