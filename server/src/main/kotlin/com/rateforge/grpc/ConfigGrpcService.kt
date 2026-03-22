package com.rateforge.grpc

import com.rateforge.algorithm.AlgorithmType
import com.rateforge.policy.NoMatchBehavior
import com.rateforge.policy.Policy
import com.rateforge.policy.PolicyCache
import com.rateforge.policy.PolicyEntity
import com.rateforge.policy.PolicyRepository
import com.rateforge.policy.toDomain
import org.springframework.data.domain.PageRequest
import com.rateforge.proto.AlgorithmTypeProto
import com.rateforge.proto.ConfigServiceGrpcKt
import com.rateforge.proto.CreatePolicyRequest
import com.rateforge.proto.DeletePolicyRequest
import com.rateforge.proto.DeletePolicyResponse
import com.rateforge.proto.GetPolicyRequest
import com.rateforge.proto.ListPoliciesRequest
import com.rateforge.proto.ListPoliciesResponse
import com.rateforge.proto.NoMatchBehaviorProto
import com.rateforge.proto.PolicyProto
import com.rateforge.proto.PolicyResponse
import com.rateforge.proto.UpdatePolicyRequest
import io.grpc.Status
import io.grpc.StatusRuntimeException
import net.devh.boot.grpc.server.service.GrpcService
import org.slf4j.LoggerFactory
import org.springframework.data.repository.findByIdOrNull
import java.util.UUID

@GrpcService
class ConfigGrpcService(
    private val policyRepository: PolicyRepository,
    private val policyCache: PolicyCache
) : ConfigServiceGrpcKt.ConfigServiceCoroutineImplBase() {

    private val log = LoggerFactory.getLogger(ConfigGrpcService::class.java)

    override suspend fun createPolicy(request: CreatePolicyRequest): PolicyResponse {
        log.info("createPolicy called: name={} algorithm={}", request.name, request.algorithm)
        try {
            if (request.name.isBlank()) {
                throw StatusRuntimeException(Status.INVALID_ARGUMENT.withDescription("Policy name is required"))
            }
            if (policyRepository.existsByName(request.name)) {
                throw StatusRuntimeException(Status.ALREADY_EXISTS.withDescription("Policy with name '${request.name}' already exists"))
            }

            val algorithm = request.algorithm.toDomain()
                ?: throw StatusRuntimeException(Status.INVALID_ARGUMENT.withDescription("Invalid algorithm type"))

            if (algorithm == AlgorithmType.TOKEN_BUCKET) {
                if (request.bucketSize <= 0) {
                    throw StatusRuntimeException(Status.INVALID_ARGUMENT.withDescription("Token bucket requires bucketSize > 0"))
                }
                if (request.refillRate <= 0.0) {
                    throw StatusRuntimeException(Status.INVALID_ARGUMENT.withDescription("Token bucket requires refillRate > 0"))
                }
            }

            val entity = PolicyEntity(
                name = request.name,
                clientId = request.clientId.ifEmpty { "*" },
                endpoint = request.endpoint.ifEmpty { "*" },
                method = request.method.ifEmpty { "*" },
                algorithm = algorithm,
                limit = request.limit,
                windowMs = request.windowMs,
                bucketSize = if (request.bucketSize > 0) request.bucketSize else null,
                refillRate = if (request.refillRate > 0.0) request.refillRate else null,
                cost = if (request.cost > 0) request.cost else 1L,
                priority = if (request.priority > 0) request.priority else 100,
                noMatchBehavior = request.noMatchBehavior.toDomain(),
                enabled = request.enabled
            )
            log.info("createPolicy saving entity: algorithm={} limit={} windowMs={}", entity.algorithm, entity.limit, entity.windowMs)
            val saved = policyRepository.save(entity)
            policyCache.invalidate()

            log.info("Created policy: id={} name={}", saved.id, saved.name)
            return policyResponse { policy = saved.toDomain().toProto() }
        } catch (ex: StatusRuntimeException) {
            throw ex
        } catch (ex: Exception) {
            log.error("createPolicy FAILED: {}", ex.message, ex)
            throw Status.INTERNAL.withDescription("createPolicy failed: ${ex.javaClass.simpleName}: ${ex.message}").asRuntimeException()
        }
    }

    override suspend fun updatePolicy(request: UpdatePolicyRequest): PolicyResponse {
        val id = runCatching { UUID.fromString(request.id) }.getOrElse {
            throw StatusRuntimeException(Status.INVALID_ARGUMENT.withDescription("Invalid policy ID format"))
        }

        val entity = policyRepository.findByIdOrNull(id)
            ?: throw StatusRuntimeException(Status.NOT_FOUND.withDescription("Policy not found: ${request.id}"))

        if (request.name.isNotBlank()) entity.name = request.name
        if (request.clientId.isNotBlank()) entity.clientId = request.clientId
        if (request.endpoint.isNotBlank()) entity.endpoint = request.endpoint
        if (request.method.isNotBlank()) entity.method = request.method
        if (request.algorithm != AlgorithmTypeProto.ALGORITHM_TYPE_UNSPECIFIED) {
            entity.algorithm = request.algorithm.toDomain()
                ?: throw StatusRuntimeException(Status.INVALID_ARGUMENT.withDescription("Invalid algorithm type"))
        }
        if (request.limit > 0) entity.limit = request.limit
        if (request.windowMs > 0) entity.windowMs = request.windowMs
        if (request.bucketSize > 0) entity.bucketSize = request.bucketSize
        if (request.refillRate > 0.0) entity.refillRate = request.refillRate
        if (request.cost > 0) entity.cost = request.cost
        if (request.priority > 0) entity.priority = request.priority
        // Only update noMatchBehavior if explicitly set (non-UNSPECIFIED)
        if (request.noMatchBehavior != NoMatchBehaviorProto.NO_MATCH_BEHAVIOR_UNSPECIFIED) {
            entity.noMatchBehavior = request.noMatchBehavior.toDomain()
        }
        // Only update enabled if the request explicitly sends true (proto3 bool default is false = "not set")
        if (request.enabled) {
            entity.enabled = true
        }

        val saved = policyRepository.save(entity)
        policyCache.invalidate()

        log.info("Updated policy: id={} name={}", saved.id, saved.name)
        return policyResponse { policy = saved.toDomain().toProto() }
    }

    override suspend fun deletePolicy(request: DeletePolicyRequest): DeletePolicyResponse {
        val id = runCatching { UUID.fromString(request.id) }.getOrElse {
            throw StatusRuntimeException(Status.INVALID_ARGUMENT.withDescription("Invalid policy ID format"))
        }

        if (!policyRepository.existsById(id)) {
            throw StatusRuntimeException(Status.NOT_FOUND.withDescription("Policy not found: ${request.id}"))
        }

        policyRepository.deleteById(id)
        policyCache.invalidate()

        log.info("Deleted policy: id={}", id)
        return DeletePolicyResponse.newBuilder()
            .setSuccess(true)
            .setMessage("Policy deleted successfully")
            .build()
    }

    override suspend fun getPolicy(request: GetPolicyRequest): PolicyResponse {
        val id = runCatching { UUID.fromString(request.id) }.getOrElse {
            throw StatusRuntimeException(Status.INVALID_ARGUMENT.withDescription("Invalid policy ID format"))
        }

        val entity = policyRepository.findByIdOrNull(id)
            ?: throw StatusRuntimeException(Status.NOT_FOUND.withDescription("Policy not found: ${request.id}"))

        return policyResponse { policy = entity.toDomain().toProto() }
    }

    override suspend fun listPolicies(request: ListPoliciesRequest): ListPoliciesResponse {
        val pageSize = request.pageSize.takeIf { it > 0 } ?: 50
        val pageNumber = request.page.coerceAtLeast(0)
        val pageable = PageRequest.of(pageNumber, pageSize)

        val pageResult = if (request.enabledOnly) {
            val page = policyRepository.findAllEnabledOrderByPriority(pageable)
            Pair(page.content, page.totalElements)
        } else {
            val page = policyRepository.findAll(pageable)
            Pair(page.content, page.totalElements)
        }

        val (entities, total) = pageResult
        return ListPoliciesResponse.newBuilder()
            .addAllPolicies(entities.map { it.toDomain().toProto() })
            .setTotalCount(total.toInt())
            .setPage(pageNumber)
            .setPageSize(pageSize)
            .build()
    }

    private fun policyResponse(block: PolicyResponse.Builder.() -> Unit): PolicyResponse =
        PolicyResponse.newBuilder().apply(block).build()
}

// Extension functions for proto <-> domain conversions

fun AlgorithmTypeProto.toDomain(): AlgorithmType? = when (this) {
    AlgorithmTypeProto.ALGORITHM_TYPE_FIXED_WINDOW -> AlgorithmType.FIXED_WINDOW
    AlgorithmTypeProto.ALGORITHM_TYPE_SLIDING_WINDOW -> AlgorithmType.SLIDING_WINDOW
    AlgorithmTypeProto.ALGORITHM_TYPE_TOKEN_BUCKET -> AlgorithmType.TOKEN_BUCKET
    else -> null
}

fun AlgorithmType.toProto(): AlgorithmTypeProto = when (this) {
    AlgorithmType.FIXED_WINDOW -> AlgorithmTypeProto.ALGORITHM_TYPE_FIXED_WINDOW
    AlgorithmType.SLIDING_WINDOW -> AlgorithmTypeProto.ALGORITHM_TYPE_SLIDING_WINDOW
    AlgorithmType.TOKEN_BUCKET -> AlgorithmTypeProto.ALGORITHM_TYPE_TOKEN_BUCKET
}

fun NoMatchBehaviorProto.toDomain(): NoMatchBehavior? = when (this) {
    NoMatchBehaviorProto.NO_MATCH_BEHAVIOR_FAIL_OPEN -> NoMatchBehavior.FAIL_OPEN
    NoMatchBehaviorProto.NO_MATCH_BEHAVIOR_FAIL_CLOSED -> NoMatchBehavior.FAIL_CLOSED
    else -> null
}

fun NoMatchBehavior.toProto(): NoMatchBehaviorProto = when (this) {
    NoMatchBehavior.FAIL_OPEN -> NoMatchBehaviorProto.NO_MATCH_BEHAVIOR_FAIL_OPEN
    NoMatchBehavior.FAIL_CLOSED -> NoMatchBehaviorProto.NO_MATCH_BEHAVIOR_FAIL_CLOSED
}

fun Policy.toProto(): PolicyProto = PolicyProto.newBuilder()
    .setId(id.toString())
    .setName(name)
    .setClientId(clientId)
    .setEndpoint(endpoint)
    .setMethod(method)
    .setAlgorithm(algorithm.toProto())
    .setLimit(limit)
    .setWindowMs(windowMs)
    .setBucketSize(bucketSize ?: 0L)
    .setRefillRate(refillRate ?: 0.0)
    .setCost(cost)
    .setPriority(priority)
    .setNoMatchBehavior(noMatchBehavior?.toProto() ?: NoMatchBehaviorProto.NO_MATCH_BEHAVIOR_UNSPECIFIED)
    .setEnabled(enabled)
    .setCreatedAtMs(createdAt.toEpochMilli())
    .setUpdatedAtMs(updatedAt.toEpochMilli())
    .build()
