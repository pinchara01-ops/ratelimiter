package com.rateforge.grpc

import com.rateforge.algorithm.AlgorithmType
import com.rateforge.policy.*
import com.rateforge.proto.*
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import io.grpc.testing.GrpcCleanupRule
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.Assertions.*
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.data.repository.findByIdOrNull
import java.time.Instant
import java.util.*

class ConfigGrpcServiceTest {
    
    private val grpcCleanup = GrpcCleanupRule()
    
    // Mocked dependencies
    private val policyRepository = mockk<PolicyRepository>()
    private val policyCache = mockk<PolicyCache>()
    
    private lateinit var service: ConfigGrpcService
    private lateinit var client: ConfigServiceGrpcKt.ConfigServiceCoroutineStub
    
    @BeforeEach
    fun setup() {
        clearAllMocks()
        
        service = ConfigGrpcService(
            policyRepository = policyRepository,
            policyCache = policyCache
        )
        
        val serverName = InProcessServerBuilder.generateName()
        grpcCleanup.register(
            InProcessServerBuilder
                .forName(serverName)
                .directExecutor()
                .addService(service)
                .build()
                .start()
        )
        
        val channel = grpcCleanup.register(
            InProcessChannelBuilder.forName(serverName)
                .directExecutor()
                .build()
        )
        
        client = ConfigServiceGrpcKt.ConfigServiceCoroutineStub(channel)
        
        // Setup default mock behaviors
        every { policyCache.invalidate() } just Runs
    }
    
    @AfterEach
    fun teardown() {
        grpcCleanup.tearDown()
    }
    
    @Test
    fun `createPolicy should persist valid policy and invalidate cache`() = runBlocking {
        // Given
        val request = createPolicyRequest {
            name = "test-policy"
            clientId = "test-client"
            endpoint = "/api/test"
            method = "GET"
            algorithm = AlgorithmTypeProto.ALGORITHM_TYPE_FIXED_WINDOW
            limit = 100
            windowMs = 60000
            priority = 50
            enabled = true
        }
        
        val savedEntity = createTestPolicyEntity(name = "test-policy")
        
        every { policyRepository.existsByName("test-policy") } returns false
        every { policyRepository.save(any()) } returns savedEntity
        
        // When
        val response = client.createPolicy(request)
        
        // Then
        assertNotNull(response.policy)
        assertEquals("test-policy", response.policy.name)
        assertEquals("test-client", response.policy.clientId)
        assertEquals("/api/test", response.policy.endpoint)
        assertEquals("GET", response.policy.method)
        assertEquals(AlgorithmTypeProto.ALGORITHM_TYPE_FIXED_WINDOW, response.policy.algorithm)
        assertEquals(100L, response.policy.limit)
        assertEquals(60000L, response.policy.windowMs)
        assertEquals(50, response.policy.priority)
        assertTrue(response.policy.enabled)
        
        verify { policyRepository.save(any()) }
        verify { policyCache.invalidate() }
    }
    
    @Test
    fun `createPolicy should return ALREADY_EXISTS for duplicate policy name`() = runBlocking {
        // Given
        val request = createPolicyRequest {
            name = "duplicate-policy"
            algorithm = AlgorithmTypeProto.ALGORITHM_TYPE_FIXED_WINDOW
            limit = 100
            windowMs = 60000
        }
        
        every { policyRepository.existsByName("duplicate-policy") } returns true
        
        // When & Then
        val exception = assertThrows<StatusRuntimeException> {
            client.createPolicy(request)
        }
        
        assertEquals(Status.ALREADY_EXISTS.code, exception.status.code)
        assertTrue(exception.message!!.contains("Policy with name 'duplicate-policy' already exists"))
        
        verify(exactly = 0) { policyRepository.save(any()) }
        verify(exactly = 0) { policyCache.invalidate() }
    }
    
    @Test
    fun `createPolicy should validate required fields`() = runBlocking {
        // Test blank name
        val blankNameRequest = createPolicyRequest {
            name = ""
            algorithm = AlgorithmTypeProto.ALGORITHM_TYPE_FIXED_WINDOW
            limit = 100
            windowMs = 60000
        }
        
        val exception = assertThrows<StatusRuntimeException> {
            client.createPolicy(blankNameRequest)
        }
        assertEquals(Status.INVALID_ARGUMENT.code, exception.status.code)
        assertTrue(exception.message!!.contains("Policy name is required"))
    }
    
    @Test
    fun `createPolicy should validate TOKEN_BUCKET requirements`() = runBlocking {
        // Test missing bucketSize
        val request1 = createPolicyRequest {
            name = "token-bucket-policy"
            algorithm = AlgorithmTypeProto.ALGORITHM_TYPE_TOKEN_BUCKET
            limit = 100
            windowMs = 60000
            bucketSize = 0 // Invalid
            refillRate = 1.0
        }
        
        val exception1 = assertThrows<StatusRuntimeException> {
            client.createPolicy(request1)
        }
        assertEquals(Status.INVALID_ARGUMENT.code, exception1.status.code)
        assertTrue(exception1.message!!.contains("Token bucket requires bucketSize > 0"))
        
        // Test missing refillRate
        val request2 = createPolicyRequest {
            name = "token-bucket-policy"
            algorithm = AlgorithmTypeProto.ALGORITHM_TYPE_TOKEN_BUCKET
            limit = 100
            windowMs = 60000
            bucketSize = 50
            refillRate = 0.0 // Invalid
        }
        
        val exception2 = assertThrows<StatusRuntimeException> {
            client.createPolicy(request2)
        }
        assertEquals(Status.INVALID_ARGUMENT.code, exception2.status.code)
        assertTrue(exception2.message!!.contains("Token bucket requires refillRate > 0"))
    }
    
    @Test
    fun `createPolicy should set default values for optional fields`() = runBlocking {
        // Given
        val request = createPolicyRequest {
            name = "minimal-policy"
            algorithm = AlgorithmTypeProto.ALGORITHM_TYPE_FIXED_WINDOW
            limit = 50
            windowMs = 30000
            enabled = true
        }
        
        val savedEntitySlot = slot<PolicyEntity>()
        every { policyRepository.existsByName("minimal-policy") } returns false
        every { policyRepository.save(capture(savedEntitySlot)) } returnsArgument 0
        
        // When
        client.createPolicy(request)
        
        // Then
        val savedEntity = savedEntitySlot.captured
        assertEquals("*", savedEntity.clientId) // Default
        assertEquals("*", savedEntity.endpoint) // Default  
        assertEquals("*", savedEntity.method) // Default
        assertEquals(1L, savedEntity.cost) // Default
        assertEquals(100, savedEntity.priority) // Default
        assertNull(savedEntity.noMatchBehavior) // Default
        assertTrue(savedEntity.enabled)
    }
    
    @Test
    fun `updatePolicy should modify existing policy and invalidate cache`() = runBlocking {
        // Given
        val existingEntity = createTestPolicyEntity(
            id = UUID.fromString("12345678-1234-1234-1234-123456789abc"),
            name = "old-name",
            enabled = false
        )
        
        val request = updatePolicyRequest {
            id = "12345678-1234-1234-1234-123456789abc"
            name = "new-name"
            limit = 200
            enabled = true // Test proto3 bool fix
        }
        
        every { policyRepository.findByIdOrNull(any<UUID>()) } returns existingEntity
        every { policyRepository.save(any()) } returnsArgument 0
        
        // When
        val response = client.updatePolicy(request)
        
        // Then
        assertEquals("new-name", response.policy.name)
        assertEquals(200L, response.policy.limit)
        assertTrue(response.policy.enabled)
        
        verify { policyRepository.save(any()) }
        verify { policyCache.invalidate() }
    }
    
    @Test
    fun `updatePolicy should handle enabled field toggle from false to true`() = runBlocking {
        // This is a regression test for proto3 bool default handling
        // Given
        val existingEntity = createTestPolicyEntity(enabled = false)
        val updateRequest = updatePolicyRequest {
            id = existingEntity.id.toString()
            enabled = true
        }
        
        val updatedEntitySlot = slot<PolicyEntity>()
        every { policyRepository.findByIdOrNull(any<UUID>()) } returns existingEntity
        every { policyRepository.save(capture(updatedEntitySlot)) } returnsArgument 0
        
        // When
        client.updatePolicy(updateRequest)
        
        // Then
        val updatedEntity = updatedEntitySlot.captured
        assertTrue(updatedEntity.enabled) // Should be updated to true
    }
    
    @Test
    fun `updatePolicy should return NOT_FOUND for non-existent policy`() = runBlocking {
        // Given
        val request = updatePolicyRequest {
            id = "00000000-0000-0000-0000-000000000000"
            name = "new-name"
        }
        
        every { policyRepository.findByIdOrNull(any<UUID>()) } returns null
        
        // When & Then
        val exception = assertThrows<StatusRuntimeException> {
            client.updatePolicy(request)
        }
        
        assertEquals(Status.NOT_FOUND.code, exception.status.code)
        assertTrue(exception.message!!.contains("Policy not found"))
        
        verify(exactly = 0) { policyRepository.save(any()) }
        verify(exactly = 0) { policyCache.invalidate() }
    }
    
    @Test
    fun `updatePolicy should validate UUID format`() = runBlocking {
        // Given
        val request = updatePolicyRequest {
            id = "invalid-uuid-format"
            name = "new-name"
        }
        
        // When & Then
        val exception = assertThrows<StatusRuntimeException> {
            client.updatePolicy(request)
        }
        
        assertEquals(Status.INVALID_ARGUMENT.code, exception.status.code)
        assertTrue(exception.message!!.contains("Invalid policy ID format"))
    }
    
    @Test
    fun `deletePolicy should remove policy from DB and invalidate cache`() = runBlocking {
        // Given
        val policyId = UUID.randomUUID()
        val request = deletePolicyRequest {
            id = policyId.toString()
        }
        
        every { policyRepository.existsById(policyId) } returns true
        every { policyRepository.deleteById(policyId) } just Runs
        
        // When
        val response = client.deletePolicy(request)
        
        // Then
        assertTrue(response.success)
        assertEquals("Policy deleted successfully", response.message)
        
        verify { policyRepository.deleteById(policyId) }
        verify { policyCache.invalidate() }
    }
    
    @Test
    fun `deletePolicy should return NOT_FOUND for non-existent policy`() = runBlocking {
        // Given
        val policyId = UUID.randomUUID()
        val request = deletePolicyRequest {
            id = policyId.toString()
        }
        
        every { policyRepository.existsById(policyId) } returns false
        
        // When & Then
        val exception = assertThrows<StatusRuntimeException> {
            client.deletePolicy(request)
        }
        
        assertEquals(Status.NOT_FOUND.code, exception.status.code)
        assertTrue(exception.message!!.contains("Policy not found"))
        
        verify(exactly = 0) { policyRepository.deleteById(any()) }
        verify(exactly = 0) { policyCache.invalidate() }
    }
    
    @Test
    fun `deletePolicy should validate UUID format`() = runBlocking {
        // Given
        val request = deletePolicyRequest {
            id = "not-a-valid-uuid"
        }
        
        // When & Then
        val exception = assertThrows<StatusRuntimeException> {
            client.deletePolicy(request)
        }
        
        assertEquals(Status.INVALID_ARGUMENT.code, exception.status.code)
        assertTrue(exception.message!!.contains("Invalid policy ID format"))
    }
    
    @Test
    fun `listPolicies should return filtered results with pagination`() = runBlocking {
        // Given
        val policy1 = createTestPolicyEntity(name = "policy1", enabled = true)
        val policy2 = createTestPolicyEntity(name = "policy2", enabled = true)
        val policy3 = createTestPolicyEntity(name = "policy3", enabled = false)
        
        val enabledPolicies = listOf(policy1, policy2)
        val page = PageImpl(enabledPolicies, PageRequest.of(0, 50), 2L)
        
        val request = listPoliciesRequest {
            enabledOnly = true
            page = 0
            pageSize = 50
        }
        
        every { policyRepository.findAllEnabledOrderByPriority(any()) } returns page
        
        // When
        val response = client.listPolicies(request)
        
        // Then
        assertEquals(2, response.policiesCount)
        assertEquals(2, response.totalCount)
        assertEquals(0, response.page)
        assertEquals(50, response.pageSize)
        
        val returnedPolicies = response.policiesList
        assertEquals("policy1", returnedPolicies[0].name)
        assertEquals("policy2", returnedPolicies[1].name)
        assertTrue(returnedPolicies[0].enabled)
        assertTrue(returnedPolicies[1].enabled)
    }
    
    @Test
    fun `listPolicies should return all policies when enabledOnly is false`() = runBlocking {
        // Given
        val policy1 = createTestPolicyEntity(name = "policy1", enabled = true)
        val policy2 = createTestPolicyEntity(name = "policy2", enabled = false)
        
        val allPolicies = listOf(policy1, policy2)
        val page = PageImpl(allPolicies, PageRequest.of(0, 10), 2L)
        
        val request = listPoliciesRequest {
            enabledOnly = false
            page = 0
            pageSize = 10
        }
        
        every { policyRepository.findAll(any<PageRequest>()) } returns page
        
        // When
        val response = client.listPolicies(request)
        
        // Then
        assertEquals(2, response.policiesCount)
        assertEquals(2, response.totalCount)
        assertEquals(0, response.page)
        assertEquals(10, response.pageSize)
        
        val returnedPolicies = response.policiesList
        assertTrue(returnedPolicies[0].enabled)
        assertFalse(returnedPolicies[1].enabled)
    }
    
    @Test
    fun `listPolicies should apply default pagination parameters`() = runBlocking {
        // Given
        val policies = listOf(createTestPolicyEntity())
        val page = PageImpl(policies, PageRequest.of(0, 50), 1L)
        
        val request = listPoliciesRequest {
            enabledOnly = false
            // No page or pageSize specified - should use defaults
        }
        
        val pageRequestSlot = slot<PageRequest>()
        every { policyRepository.findAll(capture(pageRequestSlot)) } returns page
        
        // When
        val response = client.listPolicies(request)
        
        // Then
        assertEquals(0, response.page) // Default page 0
        assertEquals(50, response.pageSize) // Default pageSize 50
        
        val capturedPageRequest = pageRequestSlot.captured
        assertEquals(0, capturedPageRequest.pageNumber)
        assertEquals(50, capturedPageRequest.pageSize)
    }
    
    private fun createTestPolicyEntity(
        id: UUID = UUID.randomUUID(),
        name: String = "test-policy",
        enabled: Boolean = true
    ): PolicyEntity {
        return PolicyEntity(
            id = id,
            name = name,
            clientId = "*",
            endpoint = "*",
            method = "*",
            algorithm = AlgorithmType.FIXED_WINDOW,
            limit = 100L,
            windowMs = 60000L,
            bucketSize = null,
            refillRate = null,
            cost = 1L,
            priority = 100,
            noMatchBehavior = null,
            enabled = enabled,
            createdAt = Instant.now(),
            updatedAt = Instant.now()
        )
    }
}