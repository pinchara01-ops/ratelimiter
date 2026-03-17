package com.rateforge.config.grpc

import com.rateforge.config.entity.PolicyEntity
import com.rateforge.config.grpc.proto.*
import com.rateforge.config.repository.PolicyRepository
import io.grpc.Status
import net.devh.boot.grpc.server.service.GrpcService
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.EmptyResultDataAccessException
import java.util.UUID

/**
 * ConfigService gRPC implementation (RAT-9).
 * Provides CRUD for rate-limit policies stored in PostgreSQL.
 */
@GrpcService
class ConfigServiceImpl(
    private val repo: PolicyRepository,
) : ConfigServiceGrpcKt.ConfigServiceCoroutineImplBase() {

    private val log = LoggerFactory.getLogger(ConfigServiceImpl::class.java)

    override suspend fun createPolicy(request: CreatePolicyRequest): CreatePolicyResponse {
        val proto = request.policy
        val id    = proto.id.ifBlank { UUID.randomUUID().toString() }

        // Input validation
        require(proto.limit > 0) { "limit must be > 0, got ${proto.limit}" }
        require(proto.windowSeconds > 0) { "windowSeconds must be > 0, got ${proto.windowSeconds}" }

        val entity = PolicyEntity(
            id               = id,
            limit            = proto.limit,
            windowSeconds    = proto.windowSeconds,
            algorithm        = proto.algorithm.name,
            priority         = proto.priority,
            clientKeyPattern = proto.clientKeyPattern.ifBlank { null },
            endpointPattern  = proto.endpointPattern.ifBlank { null },
        )
        // Use saveAndFlush + catch to avoid TOCTOU between existsById and save
        return try {
            val saved = repo.saveAndFlush(entity)
            log.info("Created policy id={} algorithm={} limit={}", saved.id, saved.algorithm, saved.limit)
            CreatePolicyResponse.newBuilder().setPolicy(saved.toProto()).build()
        } catch (ex: DataIntegrityViolationException) {
            throw Status.ALREADY_EXISTS
                .withDescription("Policy id=$id already exists")
                .asRuntimeException()
        } catch (ex: IllegalArgumentException) {
            throw Status.INVALID_ARGUMENT.withDescription(ex.message).asRuntimeException()
        }
    }

    override suspend fun updatePolicy(request: UpdatePolicyRequest): UpdatePolicyResponse {
        val proto = request.policy

        // Input validation
        require(proto.limit > 0) { "limit must be > 0, got ${proto.limit}" }
        require(proto.windowSeconds > 0) { "windowSeconds must be > 0, got ${proto.windowSeconds}" }

        val entity = repo.findById(proto.id).orElseThrow {
            Status.NOT_FOUND.withDescription("Policy id=${proto.id} not found").asRuntimeException()
        }
        entity.limit            = proto.limit
        entity.windowSeconds    = proto.windowSeconds
        entity.algorithm        = proto.algorithm.name
        entity.priority         = proto.priority
        entity.clientKeyPattern = proto.clientKeyPattern.ifBlank { null }
        entity.endpointPattern  = proto.endpointPattern.ifBlank { null }
        // updatedAt is managed by @PreUpdate on PolicyEntity — no manual set needed
        return try {
            val saved = repo.saveAndFlush(entity)
            log.info("Updated policy id={}", saved.id)
            UpdatePolicyResponse.newBuilder().setPolicy(saved.toProto()).build()
        } catch (ex: IllegalArgumentException) {
            throw Status.INVALID_ARGUMENT.withDescription(ex.message).asRuntimeException()
        }
    }

    override suspend fun deletePolicy(request: DeletePolicyRequest): DeletePolicyResponse {
        // Delete directly — catch instead of existsById+deleteById to avoid TOCTOU
        return try {
            repo.deleteById(request.id)
            log.info("Deleted policy id={}", request.id)
            DeletePolicyResponse.newBuilder().setDeleted(true).build()
        } catch (ex: EmptyResultDataAccessException) {
            throw Status.NOT_FOUND
                .withDescription("Policy id=${request.id} not found")
                .asRuntimeException()
        }
    }

    override suspend fun listPolicies(request: ListPoliciesRequest): ListPoliciesResponse {
        // Sort pushed to the DB — avoids full heap load + in-memory sort
        val protos = repo.findAllByOrderByPriorityDesc().map { it.toProto() }
        return ListPoliciesResponse.newBuilder().addAllPolicies(protos).build()
    }

    override suspend fun getPolicy(request: GetPolicyRequest): GetPolicyResponse {
        val entity = repo.findById(request.id).orElseThrow {
            Status.NOT_FOUND.withDescription("Policy id=${request.id} not found").asRuntimeException()
        }
        return GetPolicyResponse.newBuilder().setPolicy(entity.toProto()).build()
    }

    // -------------------------------------------------------------------------

    private fun PolicyEntity.toProto(): PolicyProto {
        // Guard valueOf — stale DB value → INTERNAL instead of unhandled IllegalArgumentException
        val algo = runCatching { Algorithm.valueOf(algorithm) }.getOrElse {
            throw Status.INTERNAL
                .withDescription("Stored algorithm '$algorithm' is not a known enum value for policy id=$id")
                .asRuntimeException()
        }
        return PolicyProto.newBuilder()
            .setId(id)
            .setLimit(limit)
            .setWindowSeconds(windowSeconds)
            .setAlgorithm(algo)
            .setPriority(priority)
            .setClientKeyPattern(clientKeyPattern ?: "")
            .setEndpointPattern(endpointPattern ?: "")
            .setCreatedAtMs(createdAt.toEpochMilli())
            .setUpdatedAtMs(updatedAt.toEpochMilli())
            .build()
    }
}
