package com.ris.imagedistributor.ui.settings

import app.cash.turbine.test
import com.ris.imagedistributor.data.repository.RetentionRepository
import com.ris.imagedistributor.domain.AppResult
import com.ris.imagedistributor.domain.FailureReason
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private lateinit var repository: RetentionRepository
    private lateinit var viewModel: SettingsViewModel
    private val retentionDaysFlow = MutableStateFlow(30)

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        repository = mockk()
        every { repository.observeRetentionDays() } returns retentionDaysFlow
        viewModel = SettingsViewModel(repository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `retentionDays starts null until the repository flow emits, then reflects it`() = runTest {
        viewModel.retentionDays.test {
            assertNull(awaitItem())
            retentionDaysFlow.value = 60
            assertEquals(60, awaitItem())
        }
    }

    @Test
    fun `onSave calls setRetentionDays with the given value and reports success`() = runTest {
        coEvery { repository.setRetentionDays(60) } returns AppResult.Success(Unit)
        var reported: Boolean? = null

        viewModel.onSave(60) { success -> reported = success }
        testScheduler.advanceUntilIdle()

        assertEquals(true, reported)
        coVerify { repository.setRetentionDays(60) }
        assertFalse(viewModel.isSaving.value)
    }

    @Test
    fun `onSave reports failure when the repository call fails`() = runTest {
        coEvery { repository.setRetentionDays(any()) } returns AppResult.Failure(FailureReason.DATABASE_ERROR)
        var reported: Boolean? = null

        viewModel.onSave(60) { success -> reported = success }
        testScheduler.advanceUntilIdle()

        assertEquals(false, reported)
    }

    @Test
    fun `onSave ignores a second call while one is already in flight`() = runTest {
        coEvery { repository.setRetentionDays(any()) } returns AppResult.Success(Unit)

        viewModel.onSave(60) { }
        viewModel.onSave(90) { }
        testScheduler.advanceUntilIdle()

        coVerify(exactly = 1) { repository.setRetentionDays(any()) }
    }
}
