package com.rateforge.config.grpc

import com.rateforge.config.grpc.proto.*
import com.rateforge.config.repository.PolicyRepository
import io.grpc.StatusRuntimeException
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import com.rateforge.config.entity.PolicyEntity
import java.util.Optional

class ConfigServiceImplTest {

    private val repo = mockk<PolicyRepository>()
    private val svc  = ConfigServiceImpl(repo)

    @BeforeEach fun reset() = clearAllMocks()

    private fun entity(id: String = "p1") = PolicyEntity(
        id = id, limit = 100L, windowSeconds = 60L,
        algorithm = "FIXED_WINDOW", priority = 1,
    )

    @Test fun `createPolicy persists and returns proto`() = runTest {
        every { repo.existsById("p1") } returns false
        every { repo.save(any()) } answers { firstArg() }

        val req = CreatePolicyRequest.newBuilder()
            .setPolicy(PolicyProto.newBuilder()
                .setId("p1").setLimit(100).setWindowSeconds(60)
                .setAlgorithm(Algorithm.FIXED_WINDOW).setPriority(1))
            .build()
        val resp = svc.createPolicy(req)
        assertEquals("p1", resp.policy.id)
        assertEquals(100L, resp.policy.limit)
    }

    @Test fun `createPolicy throws ALREADY_EXISTS when id taken`() = runTest {
        every { repo.existsById("p1") } returns true
        val req = CreatePolicyRequest.newBuilder()
            .setPolicy(PolicyProto.newBuilder().setId("p1")).build()
        assertThrows<StatusRuntimeException> { svc.createPolicy(req) }
    }

    @Test fun `updatePolicy updates fields`() = runTest {
        val e = entity()
        every { repo.findById("p1") } returns Optional.of(e)
        every { repo.save(any()) } answers { firstArg() }

        val req = UpdatePolicyRequest.newBuilder()
            .setPolicy(PolicyProto.newBuilder()
                .setId("p1").setLimit(200).setWindowSeconds(30)
                .setAlgorithm(Algorithm.SLIDING_WINDOW).setPriority(5))
            .build()
        val resp = svc.updatePolicy(req)
        assertEquals(200L, resp.policy.limit)
        assertEquals(Algorithm.SLIDING_WINDOW, resp.policy.algorithm)
    }

    @Test fun `deletePolicy removes entry`() = runTest {
        every { repo.existsById("p1") } returns true
        every { repo.deleteById("p1") } just runs

        val resp = svc.deletePolicy(DeletePolicyRequest.newBuilder().setId("p1").build())
        assertTrue(resp.deleted)
        verify { repo.deleteById("p1") }
    }

    @Test fun `deletePolicy throws NOT_FOUND when missing`() = runTest {
        every { repo.existsById("missing") } returns false
        assertThrows<StatusRuntimeException> {
            svc.deletePolicy(DeletePolicyRequest.newBuilder().setId("missing").build())
        }
    }

    @Test fun `listPolicies returns all sorted by priority desc`() = runTest {
        every { repo.findAll() } returns listOf(entity("p1"), entity("p2"))
        val resp = svc.listPolicies(ListPoliciesRequest.getDefaultInstance())
        assertEquals(2, resp.policiesCount)
    }
}
