package com.rateforge.policy

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface PolicyRepository : JpaRepository<PolicyEntity, UUID> {

    @Query("SELECT p FROM PolicyEntity p WHERE p.enabled = true ORDER BY p.priority ASC")
    fun findAllEnabledOrderByPriority(): List<PolicyEntity>

    @Query("SELECT p FROM PolicyEntity p WHERE p.enabled = true ORDER BY p.priority ASC")
    fun findAllEnabledOrderByPriority(pageable: Pageable): Page<PolicyEntity>

    fun findByName(name: String): PolicyEntity?

    fun existsByName(name: String): Boolean

    @Query("SELECT COUNT(p) FROM PolicyEntity p WHERE p.enabled = true")
    fun countEnabled(): Long
}
