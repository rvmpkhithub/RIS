package com.ris.imagedistributor.domain

import com.ris.imagedistributor.data.local.ComplianceState
import com.ris.imagedistributor.data.repository.ComplianceRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class ComplianceGateTest {

    private lateinit var repository: ComplianceRepository
    private lateinit var gate: ComplianceGate

    @Before
    fun setUp() {
        repository = mockk(relaxed = true)
        gate = ComplianceGate(repository)
    }

    @Test
    fun `live compliant response proceeds and records the result`() = runTest {
        coEvery { repository.checkCompliance("Arjun", "Pune") } returns AppResult.Success(true)

        val result = gate.evaluate("Arjun", "Pune")

        assertEquals(GateResult.Proceed, result)
        coVerify { repository.recordCheckResult(isCompliant = true, checkedAt = any()) }
    }

    @Test
    fun `explicit non-compliant response halts and records the result`() = runTest {
        coEvery { repository.checkCompliance("Arjun", "Pune") } returns AppResult.Success(false)

        val result = gate.evaluate("Arjun", "Pune")

        assertEquals(GateResult.Halt, result)
        coVerify { repository.recordCheckResult(isCompliant = false, checkedAt = any()) }
    }

    @Test
    fun `unreachable compliance API with no prior confirmed halt fails open`() = runTest {
        coEvery { repository.checkCompliance("Arjun", "Pune") } returns
            AppResult.Failure(FailureReason.NETWORK_UNREACHABLE)
        coEvery { repository.getState() } returns AppResult.Success(
            ComplianceState(nickname = "Arjun", city = "Pune", locked = true, isCompliant = true, lastCheckedAt = 1000L)
        )

        val result = gate.evaluate("Arjun", "Pune")

        assertEquals(GateResult.Proceed, result)
        coVerify(exactly = 0) { repository.recordCheckResult(any(), any()) }
    }

    @Test
    fun `unreachable compliance API with no cached state yet fails open`() = runTest {
        coEvery { repository.checkCompliance("Arjun", "Pune") } returns
            AppResult.Failure(FailureReason.NETWORK_UNREACHABLE)
        coEvery { repository.getState() } returns AppResult.Success(null)

        val result = gate.evaluate("Arjun", "Pune")

        assertEquals(GateResult.Proceed, result)
    }

    @Test
    fun `unreachable compliance API extends a previously confirmed non-compliant halt`() = runTest {
        coEvery { repository.checkCompliance("Arjun", "Pune") } returns
            AppResult.Failure(FailureReason.NETWORK_UNREACHABLE)
        coEvery { repository.getState() } returns AppResult.Success(
            ComplianceState(nickname = "Arjun", city = "Pune", locked = true, isCompliant = false, lastCheckedAt = 1000L)
        )

        val result = gate.evaluate("Arjun", "Pune")

        assertEquals(GateResult.Halt, result)
    }

    @Test
    fun `unreachable compliance API AND unreadable cache fails open, never fabricating a halt`() = runTest {
        coEvery { repository.checkCompliance("Arjun", "Pune") } returns
            AppResult.Failure(FailureReason.NETWORK_UNREACHABLE)
        coEvery { repository.getState() } returns AppResult.Failure(FailureReason.DATABASE_ERROR)

        val result = gate.evaluate("Arjun", "Pune")

        assertEquals(GateResult.Proceed, result)
    }

    @Test
    fun `server error fails open just like a network failure`() = runTest {
        coEvery { repository.checkCompliance("Arjun", "Pune") } returns
            AppResult.Failure(FailureReason.SERVER_ERROR)
        coEvery { repository.getState() } returns AppResult.Success(
            ComplianceState(nickname = "Arjun", city = "Pune", locked = true, isCompliant = true, lastCheckedAt = 1000L)
        )

        val result = gate.evaluate("Arjun", "Pune")

        assertEquals(GateResult.Proceed, result)
    }
}
