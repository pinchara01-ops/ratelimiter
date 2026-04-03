package com.rateforge.lifecycle

import io.grpc.ForwardingServerCall
import io.grpc.Metadata
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.ServerInterceptor
import io.grpc.Status
import net.devh.boot.grpc.server.interceptor.GrpcGlobalServerInterceptor
import org.springframework.core.annotation.Order

/**
 * gRPC interceptor that tracks in-flight requests and rejects new requests
 * during graceful shutdown.
 */
@GrpcGlobalServerInterceptor
@Order(1) // Run first to reject requests early during shutdown
class GracefulShutdownInterceptor(
    private val shutdownManager: GracefulShutdownManager
) : ServerInterceptor {
    
    override fun <ReqT, RespT> interceptCall(
        call: ServerCall<ReqT, RespT>,
        headers: Metadata,
        next: ServerCallHandler<ReqT, RespT>
    ): ServerCall.Listener<ReqT> {
        // Check if we can accept this request
        if (!shutdownManager.requestStarted()) {
            // Application is shutting down, reject the request
            call.close(
                Status.UNAVAILABLE.withDescription("Server is shutting down"),
                Metadata()
            )
            return NoOpListener()
        }
        
        // Wrap the call to track completion
        val trackingCall = object : ForwardingServerCall.SimpleForwardingServerCall<ReqT, RespT>(call) {
            @Volatile
            private var completed = false
            
            override fun close(status: Status, trailers: Metadata) {
                if (!completed) {
                    completed = true
                    shutdownManager.requestCompleted()
                }
                super.close(status, trailers)
            }
        }
        
        return object : ServerCall.Listener<ReqT>() {
            private val delegate = next.startCall(trackingCall, headers)
            @Volatile
            private var cancelled = false
            
            override fun onMessage(message: ReqT) {
                delegate.onMessage(message)
            }
            
            override fun onHalfClose() {
                delegate.onHalfClose()
            }
            
            override fun onCancel() {
                if (!cancelled) {
                    cancelled = true
                    // Ensure we track completion even on cancel
                    try {
                        delegate.onCancel()
                    } finally {
                        shutdownManager.requestCompleted()
                    }
                }
            }
            
            override fun onComplete() {
                delegate.onComplete()
            }
            
            override fun onReady() {
                delegate.onReady()
            }
        }
    }
    
    /**
     * No-op listener for rejected requests.
     */
    private class NoOpListener<T> : ServerCall.Listener<T>() {
        override fun onMessage(message: T) {}
        override fun onHalfClose() {}
        override fun onCancel() {}
        override fun onComplete() {}
        override fun onReady() {}
    }
}
