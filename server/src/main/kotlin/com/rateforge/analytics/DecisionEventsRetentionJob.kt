package com.rateforge.analytics

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Scheduled job to clean up old decision_events rows to prevent unbounded growth.
 * 
 * Runs nightly at 2 AM to delete rows older than the configured retention period.
 * Uses a batched approach for large datasets to avoid long-running transactions.
 */
@Component
class DecisionEventsRetentionJob(
    private val jdbcTemplate: JdbcTemplate,
    @Value("\${rateforge.analytics.retention-days:90}")
    private val retentionDays: Int
) {

    private val logger = LoggerFactory.getLogger(DecisionEventsRetentionJob::class.java)

    /**
     * Deletes decision_events rows older than retention period.
     * Runs daily at 2:00 AM in the server's timezone.
     * Uses batched deletes to avoid locking the table for extended periods.
     */
    @Scheduled(cron = "0 0 2 * * ?")
    fun cleanupOldDecisionEvents() {
        val cutoffTime = Instant.now().minus(retentionDays.toLong(), ChronoUnit.DAYS)
        
        logger.info("Starting decision_events cleanup: deleting rows older than {} (retention: {} days)", 
                   cutoffTime, retentionDays)
        
        try {
            var totalDeleted = 0L
            var batchDeleted: Int
            
            // Delete in batches of 10k to avoid long-running transactions
            do {
                batchDeleted = jdbcTemplate.update(
                    """
                    DELETE FROM decision_events 
                    WHERE id IN (
                        SELECT id FROM decision_events 
                        WHERE occurred_at < ? 
                        LIMIT 10000
                    )
                    """.trimIndent(),
                    cutoffTime
                )
                totalDeleted += batchDeleted
                
                if (batchDeleted > 0) {
                    logger.debug("Deleted {} rows in current batch, total so far: {}", batchDeleted, totalDeleted)
                }
            } while (batchDeleted > 0)
            
            if (totalDeleted > 0) {
                logger.info("Decision_events cleanup completed: deleted {} rows older than {} days", 
                           totalDeleted, retentionDays)
            } else {
                logger.debug("Decision_events cleanup completed: no rows to delete (retention: {} days)", 
                            retentionDays)
            }
            
        } catch (exception: Exception) {
            logger.error("Error during decision_events cleanup", exception)
            throw exception
        }
    }
    
    /**
     * Returns the current retention configuration for monitoring/health checks.
     */
    fun getRetentionDays(): Int = retentionDays
}