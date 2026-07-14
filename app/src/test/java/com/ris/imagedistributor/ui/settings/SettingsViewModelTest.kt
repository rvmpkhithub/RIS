package com.ris.imagedistributor.ui.settings

import app.cash.turbine.test
import com.ris.imagedistributor.data.repository.MasterScheduleRepository
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
    private lateinit var masterScheduleRepository: MasterScheduleRepository
    private lateinit var viewModel: SettingsViewModel
    private val retentionDaysFlow = MutableStateFlow(30)
    private val masterScheduleTimesFlow = MutableStateFlow(listOf(540, 720, 900, 1080))

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        repository = mockk()
        masterScheduleRepository = mockk()
        every { repository.observeRetentionDays() } returns retentionDaysFlow
        every { masterScheduleRepository.observeScheduleTimes() } returns masterScheduleTimesFlow
        viewModel = SettingsViewModel(repository, masterScheduleRepository)
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

    @Test
    fun `masterScheduleTimes starts null until the repository flow emits, then reflects it`() = runTest {
        viewModel.masterScheduleTimes.test {
            assertNull(awaitItem())
            masterScheduleTimesFlow.value = listOf(360, 600, 840, 1200)
            assertEquals(listOf(360, 600, 840, 1200), awaitItem())
        }
    }

    @Test
    fun `onSaveMasterSchedule calls setScheduleTimes with the given value and reports success`() = runTest {
        val newTimes = listOf(360, 600, 840, 1200)
        coEvery { masterScheduleRepository.setScheduleTimes(newTimes) } returns AppResult.Success(Unit)
        var reported: Boolean? = null

        viewModel.onSaveMasterSchedule(newTimes) { success -> reported = success }
        testScheduler.advanceUntilIdle()

        assertEquals(true, reported)
        coVerify { masterScheduleRepository.setScheduleTimes(newTimes) }
        assertFalse(viewModel.isSaving.value)
    }

    @Test
    fun `onSaveMasterSchedule reports failure when the repository call fails`() = runTest {
        coEvery { masterScheduleRepository.setScheduleTimes(any()) } returns AppResult.Failure(FailureReason.INVALID_INPUT)
        var reported: Boolean? = null

        viewModel.onSaveMasterSchedule(listOf(540)) { success -> reported = success }
        testScheduler.advanceUntilIdle()

        assertEquals(false, reported)
    }

    @Test
    fun `onSaveMasterSchedule ignores a second call while one is already in flight`() = runTest {
        coEvery { masterScheduleRepository.setScheduleTimes(any()) } returns AppResult.Success(Unit)

        viewModel.onSaveMasterSchedule(listOf(540, 720, 900, 1080)) { }
        viewModel.onSaveMasterSchedule(listOf(360, 600, 840, 1200)) { }
        testScheduler.advanceUntilIdle()

        coVerify(exactly = 1) { masterScheduleRepository.setScheduleTimes(any()) }
    }

    @Test
    fun `onSave and onSaveMasterSchedule share the same in-flight guard`() = runTest {
        coEvery { repository.setRetentionDays(any()) } returns AppResult.Success(Unit)
        coEvery { masterScheduleRepository.setScheduleTimes(any()) } returns AppResult.Success(Unit)

        viewModel.onSave(60) { }
        viewModel.onSaveMasterSchedule(listOf(540, 720, 900, 1080)) { }
        testScheduler.advanceUntilIdle()

        coVerify(exactly = 1) { repository.setRetentionDays(any()) }
        coVerify(exactly = 0) { masterScheduleRepository.setScheduleTimes(any()) }
    }
}
