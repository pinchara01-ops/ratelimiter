package com.rateforge.config.repository

import com.rateforge.config.entity.PolicyEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface PolicyRepository : JpaRepository<PolicyEntity, String> {
    /** Returns all policies sorted by priority descending — sort done in DB, not in heap. */
    fun findAllByOrderByPriorityDesc(): List<PolicyEntity>
}
