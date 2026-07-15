package com.ris.imagedistributor.ui.receivers

import app.cash.turbine.test
import com.ris.imagedistributor.data.local.Image
import com.ris.imagedistributor.data.local.Receiver
import com.ris.imagedistributor.data.local.ReceiverWithSchedules
import com.ris.imagedistributor.data.repository.ImageRepository
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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
    private lateinit var imageRepository: ImageRepository
    private lateinit var viewModel: ReceiversViewModel
    private val receiversFlow = MutableStateFlow<List<ReceiverWithSchedules>>(emptyList())

    private val receiver = Receiver(
        id = 1L,
        name = "Asha",
        channel = "WHATSAPP",
        phoneOrEmail = "+911234567890",
    )
    private val scheduleTimes = listOf(9 * 60, 12 * 60, 15 * 60, 18 * 60)
    private val receiverWithSchedules = ReceiverWithSchedules(receiver, scheduleTimes)

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        repository = mockk(relaxed = true)
        imageRepository = mockk(relaxed = true)
        every { repository.observeReceivers() } returns receiversFlow
        viewModel = ReceiversViewModel(repository, imageRepository)
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

    @Test
    fun `saveWithBudgetCheck persists and reports null when within budget`() = runTest {
        // Same reasoning as validateMinCountBudget's own tests — establish a subscriber first so
        // receivers.value reflects the (empty) list rather than its null initial seed.
        backgroundScope.launch { viewModel.receivers.collect {} }
        coEvery { repository.addReceiver(receiver, scheduleTimes) } returns AppResult.Success(Unit)
        coEvery { imageRepository.getActiveImages() } returns AppResult.Success((1..10L).map {
            com.ris.imagedistributor.data.local.Image(id = it, filePath = "$it.jpg", active = true, uploadedAt = 0L)
        })
        testScheduler.advanceUntilIdle()
        var reported: String? = "not called"

        viewModel.saveWithBudgetCheck(receiver, scheduleTimes, isNew = true, excludingReceiverId = null) { error -> reported = error }
        testScheduler.advanceUntilIdle()

        assertEquals(null, reported)
        coVerify { repository.addReceiver(receiver, scheduleTimes) }
        assertFalse(viewModel.isSaving.value)
    }

    @Test
    fun `saveWithBudgetCheck reports the budget error and does not persist when over budget`() = runTest {
        backgroundScope.launch { viewModel.receivers.collect {} }
        coEvery { imageRepository.getActiveImages() } returns AppResult.Success(emptyList())
        testScheduler.advanceUntilIdle()
        var reported: String? = null

        // receiver's own default minCount (2, per its Receiver(...) constructor default) already
        // exceeds 0 active images.
        viewModel.saveWithBudgetCheck(receiver, scheduleTimes, isNew = true, excludingReceiverId = null) { error -> reported = error }
        testScheduler.advanceUntilIdle()

        assertTrue("expected a budget error to be reported", reported != null)
        coVerify(exactly = 0) { repository.addReceiver(any(), any()) }
        assertFalse(viewModel.isSaving.value)
    }

    @Test
    fun `saveWithBudgetCheck ignores a second call while one is already in flight`() = runTest {
        backgroundScope.launch { viewModel.receivers.collect {} }
        coEvery { repository.addReceiver(receiver, scheduleTimes) } returns AppResult.Success(Unit)
        coEvery { imageRepository.getActiveImages() } returns AppResult.Success((1..10L).map {
            com.ris.imagedistributor.data.local.Image(id = it, filePath = "$it.jpg", active = true, uploadedAt = 0L)
        })
        testScheduler.advanceUntilIdle()

        viewModel.saveWithBudgetCheck(receiver, scheduleTimes, isNew = true, excludingReceiverId = null) { }
        viewModel.saveWithBudgetCheck(receiver, scheduleTimes, isNew = true, excludingReceiverId = null) { }
        testScheduler.advanceUntilIdle()

        coVerify(exactly = 1) { repository.addReceiver(receiver, scheduleTimes) }
    }

    private fun activeImages(count: Int): List<Image> =
        (1..count).map { Image(id = it.toLong(), filePath = "$it.jpg", active = true, uploadedAt = 0L) }

    /** `receivers` uses `WhileSubscribed(5000)` — its `.value` only reflects the repository flow
     * once something is actively collecting it, same as every other StateFlow in this codebase.
     * `validateMinCountBudget` reads that `.value` directly (safe in production, since the Edit
     * screen is already collecting `receivers` via `collectAsState()` by the time Save is
     * reachable); tests need to establish that same subscription first. */
    private fun TestScope.subscribeToReceivers() {
        backgroundScope.launch { viewModel.receivers.collect {} }
        testScheduler.advanceUntilIdle()
    }

    @Test
    fun `validateMinCountBudget returns null when the combined minimum fits within the active image count`() = runTest {
        receiversFlow.value = listOf(receiverWithSchedules.copy(receiver = receiver.copy(minCount = 3)))
        coEvery { imageRepository.getActiveImages() } returns AppResult.Success(activeImages(10))
        subscribeToReceivers()

        val error = viewModel.validateMinCountBudget(candidateMinCount = 4, excludingReceiverId = null)

        assertEquals(null, error) // 3 (existing) + 4 (new) = 7 <= 10 active
    }

    @Test
    fun `validateMinCountBudget returns an error when the combined minimum exceeds the active image count`() = runTest {
        receiversFlow.value = listOf(receiverWithSchedules.copy(receiver = receiver.copy(minCount = 8)))
        coEvery { imageRepository.getActiveImages() } returns AppResult.Success(activeImages(10))
        subscribeToReceivers()

        val error = viewModel.validateMinCountBudget(candidateMinCount = 5, excludingReceiverId = null)

        assertTrue("expected a non-null error message when 8 + 5 = 13 > 10 active images", error != null)
    }

    @Test
    fun `validateMinCountBudget excludes the receiver being edited from the other-receivers sum`() = runTest {
        receiversFlow.value = listOf(receiverWithSchedules.copy(receiver = receiver.copy(id = 1L, minCount = 8)))
        coEvery { imageRepository.getActiveImages() } returns AppResult.Success(activeImages(10))
        subscribeToReceivers()

        // Editing receiver 1's own minimum from 8 to 5 — must compare against 0 other receivers
        // (there are none besides itself), not double-count its own pre-edit value.
        val error = viewModel.validateMinCountBudget(candidateMinCount = 5, excludingReceiverId = 1L)

        assertEquals(null, error)
    }

    @Test
    fun `validateMinCountBudget returns a friendly message when getActiveImages fails`() = runTest {
        receiversFlow.value = emptyList()
        coEvery { imageRepository.getActiveImages() } returns AppResult.Failure(FailureReason.DATABASE_ERROR)
        subscribeToReceivers()

        val error = viewModel.validateMinCountBudget(candidateMinCount = 2, excludingReceiverId = null)

        assertTrue(error != null)
    }
}
