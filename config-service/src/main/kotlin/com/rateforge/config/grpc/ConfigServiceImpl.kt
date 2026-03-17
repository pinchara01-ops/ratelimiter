package com.rateforge.config.grpc

import com.rateforge.config.entity.PolicyEntity
import com.rateforge.config.grpc.proto.*
import com.rateforge.config.repository.PolicyRepository
import io.grpc.Status
import net.devh.boot.grpc.server.service.GrpcService
import org.slf4j.LoggerFactory
import java.time.Instant
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
        val id = proto.id.ifBlank { UUID.randomUUID().toString() }
        if (repo.existsById(id)) {
            throw Status.ALREADY_EXISTS.withDescription("Policy id=$id already exists").asRuntimeException()
        }
        val entity = PolicyEntity(
            id               = id,
            limit            = proto.limit,
            windowSeconds    = proto.windowSeconds,
            algorithm        = proto.algorithm.name,
            priority         = proto.priority,
            clientKeyPattern = proto.clientKeyPattern.ifBlank { null },
            endpointPattern  = proto.endpointPattern.ifBlank { null },
        )
        val saved = repo.save(entity)
        log.info("Created policy id={} algorithm={} limit={}", saved.id, saved.algorithm, saved.limit)
        return CreatePolicyResponse.newBuilder().setPolicy(saved.toProto()).build()
    }

    override suspend fun updatePolicy(request: UpdatePolicyRequest): UpdatePolicyResponse {
        val proto = request.policy
        val entity = repo.findById(proto.id).orElseThrow {
            Status.NOT_FOUND.withDescription("Policy id=${proto.id} not found").asRuntimeException()
        }
        entity.limit            = proto.limit
        entity.windowSeconds    = proto.windowSeconds
        entity.algorithm        = proto.algorithm.name
        entity.priority         = proto.priority
        entity.clientKeyPattern = proto.clientKeyPattern.ifBlank { null }
        entity.endpointPattern  = proto.endpointPattern.ifBlank { null }
        entity.updatedAt        = Instant.now()
        val saved = repo.save(entity)
        log.info("Updated policy id={}", saved.id)
        return UpdatePolicyResponse.newBuilder().setPolicy(saved.toProto()).build()
    }

    override suspend fun deletePolicy(request: DeletePolicyRequest): DeletePolicyResponse {
        if (!repo.existsById(request.id)) {
            throw Status.NOT_FOUND.withDescription("Policy id=${request.id} not found").asRuntimeException()
        }
        repo.deleteById(request.id)
        log.info("Deleted policy id={}", request.id)
        return DeletePolicyResponse.newBuilder().setDeleted(true).build()
    }

    override suspend fun listPolicies(request: ListPoliciesRequest): ListPoliciesResponse {
        val protos = repo.findAll().sortedByDescending { it.priority }.map { it.toProto() }
        return ListPoliciesResponse.newBuilder().addAllPolicies(protos).build()
    }

    override suspend fun getPolicy(request: GetPolicyRequest): GetPolicyResponse {
        val entity = repo.findById(request.id).orElseThrow {
            Status.NOT_FOUND.withDescription("Policy id=${request.id} not found").asRuntimeException()
        }
        return GetPolicyResponse.newBuilder().setPolicy(entity.toProto()).build()
    }

    // -------------------------------------------------------------------------

    private fun PolicyEntity.toProto(): PolicyProto = PolicyProto.newBuilder()
        .setId(id)
        .setLimit(limit)
        .setWindowSeconds(windowSeconds)
        .setAlgorithm(Algorithm.valueOf(algorithm))
        .setPriority(priority)
        .setClientKeyPattern(clientKeyPattern ?: "")
        .setEndpointPattern(endpointPattern ?: "")
        .setCreatedAtMs(createdAt.toEpochMilli())
        .setUpdatedAtMs(updatedAt.toEpochMilli())
        .build()
}
