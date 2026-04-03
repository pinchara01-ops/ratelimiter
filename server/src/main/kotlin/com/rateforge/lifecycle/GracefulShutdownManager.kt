package com.rateforge.lifecycle

import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.ApplicationListener
import org.springframework.stereotype.Component
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Manages graceful shutdown of the application.
 * 
 * Features:
 * - Tracks in-flight requests
 * - Provides health check status during shutdown
 * - Waits for requests to complete before shutdown
 * - Configurable shutdown timeout
 */
@Component
class GracefulShutdownManager(
    private val properties: ShutdownProperties
) : ApplicationListener<ApplicationReadyEvent> {
    
    private val log = LoggerFactory.getLogger(GracefulShutdownManager::class.java)
    
    private val shuttingDown = AtomicBoolean(false)
    private val ready = AtomicBoolean(false)
    private val inFlightRequests = AtomicInteger(0)
    private val shutdownLatch = CountDownLatch(1)
    
    /**
     * Returns true if the application is ready to accept requests.
     */
    fun isReady(): Boolean = ready.get() && !shuttingDown.get()
    
    /**
     * Returns true if the application is in the process of shutting down.
     */
    fun isShuttingDown(): Boolean = shuttingDown.get()
    
    /**
     * Returns the current number of in-flight requests.
     */
    fun getInFlightRequestCount(): Int = inFlightRequests.get()
    
    /**
     * Called when a request starts processing.
     * Returns false if the application is shutting down and the request should be rejected.
     */
    fun requestStarted(): Boolean {
        if (shuttingDown.get()) {
            return false
        }
        inFlightRequests.incrementAndGet()
        return true
    }
    
    /**
     * Called when a request completes (successfully or with error).
     */
    fun requestCompleted() {
        val remaining = inFlightRequests.decrementAndGet()
        if (shuttingDown.get() && remaining == 0) {
            shutdownLatch.countDown()
        }
    }
    
    override fun onApplicationEvent(event: ApplicationReadyEvent) {
        ready.set(true)
        log.info("Application ready to accept requests")
    }
    
    @PreDestroy
    fun shutdown() {
        log.info("Initiating graceful shutdown")
        shuttingDown.set(true)
        ready.set(false)
        
        val currentInFlight = inFlightRequests.get()
        if (currentInFlight > 0) {
            log.info("Waiting for {} in-flight requests to complete (timeout: {}ms)", 
                currentInFlight, properties.timeoutMs)
            
            val completed = shutdownLatch.await(properties.timeoutMs, TimeUnit.MILLISECONDS)
            
            if (!completed) {
                val remaining = inFlightRequests.get()
                log.warn("Shutdown timeout reached with {} requests still in-flight", remaining)
            } else {
                log.info("All in-flight requests completed")
            }
        } else {
            log.info("No in-flight requests, shutting down immediately")
        }
        
        log.info("Graceful shutdown complete")
    }
}
