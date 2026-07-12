package com.ris.imagedistributor.ui.receivers

import app.cash.turbine.test
import com.ris.imagedistributor.data.local.Receiver
import com.ris.imagedistributor.data.local.ReceiverWithSchedules
import com.ris.imagedistributor.data.repository.ReceiverRepository
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReceiversViewModelTest {

    private lateinit var repository: ReceiverRepository
    private lateinit var viewModel: ReceiversViewModel
    private val receiversFlow = MutableStateFlow<List<ReceiverWithSchedules>>(emptyList())

    private val receiver = Receiver(
        id = 1L,
        name = "Asha",
        channel = "WHATSAPP",
        phoneOrEmail = "+911234567890",
        minCount = 2,
        maxCount = 5,
    )
    private val scheduleTimes = listOf(9 * 60, 12 * 60, 15 * 60, 18 * 60)
    private val receiverWithSchedules = ReceiverWithSchedules(receiver, scheduleTimes)

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        repository = mockk(relaxed = true)
        every { repository.observeReceivers() } returns receiversFlow
        viewModel = ReceiversViewModel(repository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `receivers starts null until the repository flow emits, then reflects it`() = runTest {
        viewModel.receivers.test {
            assertNull(awaitItem())
            receiversFlow.value = listOf(receiverWithSchedules)
            assertEquals(listOf(receiverWithSchedules), awaitItem())
        }
    }

    @Test
    fun `deleteReceiver delegates to the repository and returns true on success`() = runTest {
        coEvery { repository.deleteReceiver(1L) } returns AppResult.Success(Unit)

        val result = viewModel.deleteReceiver(1L)

        assertTrue(result)
        coVerify { repository.deleteReceiver(1L) }
    }

    @Test
    fun `deleteReceiver returns false when the repository call fails`() = runTest {
        coEvery { repository.deleteReceiver(1L) } returns AppResult.Failure(FailureReason.DATABASE_ERROR)

        val result = viewModel.deleteReceiver(1L)

        assertFalse(result)
    }

    @Test
    fun `saveReceiver with isNew true calls addReceiver with the schedule times and returns true on success`() = runTest {
        coEvery { repository.addReceiver(receiver, scheduleTimes) } returns AppResult.Success(Unit)

        val result = viewModel.saveReceiver(receiver, scheduleTimes, isNew = true)

        assertTrue(result)
        coVerify { repository.addReceiver(receiver, scheduleTimes) }
        coVerify(exactly = 0) { repository.updateReceiver(any(), any()) }
    }

    @Test
    fun `saveReceiver with isNew false calls updateReceiver with the schedule times and returns true on success`() = runTest {
        coEvery { repository.updateReceiver(receiver, scheduleTimes) } returns AppResult.Success(Unit)

        val result = viewModel.saveReceiver(receiver, scheduleTimes, isNew = false)

        assertTrue(result)
        coVerify { repository.updateReceiver(receiver, scheduleTimes) }
        coVerify(exactly = 0) { repository.addReceiver(any(), any()) }
    }

    @Test
    fun `saveReceiver returns false when the repository call fails`() = runTest {
        coEvery { repository.addReceiver(receiver, scheduleTimes) } returns AppResult.Failure(FailureReason.DATABASE_ERROR)

        val result = viewModel.saveReceiver(receiver, scheduleTimes, isNew = true)

        assertFalse(result)
    }

    @Test
    fun `save reports success via callback and toggles isSaving`() = runTest {
        coEvery { repository.addReceiver(receiver, scheduleTimes) } returns AppResult.Success(Unit)
        var reported: Boolean? = null

        viewModel.save(receiver, scheduleTimes, isNew = true) { success -> reported = success }
        testScheduler.advanceUntilIdle()

        assertEquals(true, reported)
        assertFalse(viewModel.isSaving.value)
    }

    @Test
    fun `save ignores a second call while one is already in flight`() = runTest {
        coEvery { repository.addReceiver(receiver, scheduleTimes) } returns AppResult.Success(Unit)

        viewModel.save(receiver, scheduleTimes, isNew = true) { }
        viewModel.save(receiver, scheduleTimes, isNew = true) { }
        testScheduler.advanceUntilIdle()

        coVerify(exactly = 1) { repository.addReceiver(receiver, scheduleTimes) }
    }
}
