package com.ris.imagedistributor.ui.setup

import app.cash.turbine.test
import com.ris.imagedistributor.data.repository.ComplianceRepository
import com.ris.imagedistributor.domain.AppResult
import com.ris.imagedistributor.domain.ComplianceGate
import com.ris.imagedistributor.domain.GateResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SetupViewModelTest {

    private lateinit var repository: ComplianceRepository
    private lateinit var gate: ComplianceGate
    private lateinit var viewModel: SetupViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        repository = mockk(relaxed = true)
        gate = mockk()
        coEvery { repository.lockRegistration(any(), any()) } returns AppResult.Success(Unit)
        viewModel = SetupViewModel(repository, gate)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `cannot submit until both fields are filled`() {
        assertFalse(viewModel.uiState.value.canSubmit)
        viewModel.onNicknameChange("Arjun")
        assertFalse(viewModel.uiState.value.canSubmit)
        viewModel.onCityChange("Pune")
        assertTrue(viewModel.uiState.value.canSubmit)
    }

    @Test
    fun `submit locks registration before checking compliance, in that order`() = runTest {
        viewModel.onNicknameChange("Arjun")
        viewModel.onCityChange("Pune")
        coEvery { gate.evaluate("Arjun", "Pune") } returns GateResult.Proceed

        viewModel.onContinue()
        testScheduler.advanceUntilIdle()

        coVerifyOrder {
            repository.lockRegistration("Arjun", "Pune")
            repository.register("Arjun", "Pune")
            gate.evaluate("Arjun", "Pune")
        }
    }

    @Test
    fun `proceed result navigates to main app`() = runTest {
        viewModel.onNicknameChange("Arjun")
        viewModel.onCityChange("Pune")
        coEvery { gate.evaluate(any(), any()) } returns GateResult.Proceed

        viewModel.navEvent.test {
            assertEquals(null, awaitItem())
            viewModel.onContinue()
            testScheduler.advanceUntilIdle()
            assertEquals(SetupNavEvent.ToMainApp, awaitItem())
        }
    }

    @Test
    fun `halt result navigates to compliance halt`() = runTest {
        viewModel.onNicknameChange("Arjun")
        viewModel.onCityChange("Pune")
        coEvery { gate.evaluate(any(), any()) } returns GateResult.Halt

        viewModel.navEvent.test {
            assertEquals(null, awaitItem())
            viewModel.onContinue()
            testScheduler.advanceUntilIdle()
            assertEquals(SetupNavEvent.ToComplianceHalt, awaitItem())
        }
    }

    @Test
    fun `registration POST failure does not block submission from reaching the compliance check`() = runTest {
        viewModel.onNicknameChange("Arjun")
        viewModel.onCityChange("Pune")
        coEvery { repository.register(any(), any()) } returns AppResult.Failure(com.ris.imagedistributor.domain.FailureReason.NETWORK_UNREACHABLE)
        coEvery { gate.evaluate(any(), any()) } returns GateResult.Proceed

        viewModel.onContinue()
        testScheduler.advanceUntilIdle()

        coVerify { gate.evaluate("Arjun", "Pune") }
    }

    @Test
    fun `lockRegistration failure surfaces an error and does not proceed to registration or the compliance check`() = runTest {
        viewModel.onNicknameChange("Arjun")
        viewModel.onCityChange("Pune")
        coEvery { repository.lockRegistration("Arjun", "Pune") } returns
            AppResult.Failure(com.ris.imagedistributor.domain.FailureReason.DATABASE_ERROR)

        viewModel.onContinue()
        testScheduler.advanceUntilIdle()

        assertEquals("Couldn't save — please try again.", viewModel.uiState.value.error)
        assertFalse(viewModel.uiState.value.submitting)
        coVerify(exactly = 0) { repository.register(any(), any()) }
        coVerify(exactly = 0) { gate.evaluate(any(), any()) }
    }

    @Test
    fun `nickname and city are trimmed before being locked`() = runTest {
        viewModel.onNicknameChange("  Arjun  ")
        viewModel.onCityChange("  Pune  ")
        coEvery { gate.evaluate("Arjun", "Pune") } returns GateResult.Proceed

        viewModel.onContinue()
        testScheduler.advanceUntilIdle()

        coVerify { repository.lockRegistration("Arjun", "Pune") }
    }
}
