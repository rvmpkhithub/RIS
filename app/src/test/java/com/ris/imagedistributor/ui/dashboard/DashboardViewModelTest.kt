package com.ris.imagedistributor.ui.dashboard

import app.cash.turbine.test
import com.ris.imagedistributor.data.local.DeliveryRecord
import com.ris.imagedistributor.data.local.Image
import com.ris.imagedistributor.data.local.Receiver
import com.ris.imagedistributor.data.local.ReceiverWithSchedules
import com.ris.imagedistributor.data.repository.ReceiverRepository
import com.ris.imagedistributor.data.repository.TransmissionRepository
import com.ris.imagedistributor.domain.RetentionPolicy
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.temporal.ChronoUnit

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModelTest {

    private lateinit var receiverRepository: ReceiverRepository
    private lateinit var transmissionRepository: TransmissionRepository
    private lateinit var retentionPolicy: RetentionPolicy
    private lateinit var viewModel: DashboardViewModel
    private val receiversFlow = MutableStateFlow<List<ReceiverWithSchedules>>(emptyList())
    private val fixedNow: Instant = Instant.ofEpochMilli(10_000_000_000L)
    // [Review][Patch] the cutoff now comes from RetentionPolicy.observeCutoff(), not a raw
    // retentionDays count DashboardViewModel subtracts itself (see AD-10 fix) — the ViewModel no
    // longer has its own injectable clock, so tests control the cutoff directly instead.
    private val cutoffFlow = MutableStateFlow(fixedNow.minus(30, ChronoUnit.DAYS))

    private val receiverA = Receiver(id = 1L, name = "Asha", channel = "WHATSAPP", phoneOrEmail = "+911234567890")
    private val receiverB = Receiver(id = 2L, name = "Bala", channel = "EMAIL", phoneOrEmail = "bala@example.com")
    private val image = Image(id = 9L, filePath = "a.jpg", active = true, uploadedAt = 0L)
    private val recordA = DeliveryRecord(transmissionId = 1L, image = image, sentAt = fixedNow)

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        receiverRepository = mockk()
        transmissionRepository = mockk()
        retentionPolicy = mockk()
        every { receiverRepository.observeReceivers() } returns receiversFlow
        every { transmissionRepository.observeSentHistory(any(), any()) } returns flowOf(emptyList())
        every { retentionPolicy.observeCutoff() } returns cutoffFlow
        viewModel = DashboardViewModel(receiverRepository, transmissionRepository, retentionPolicy)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `receivers starts null until the repository flow emits, then reflects it`() = runTest {
        viewModel.receivers.test {
            assertNull(awaitItem())
            receiversFlow.value = listOf(ReceiverWithSchedules(receiverA, emptyList()))
            assertEquals(listOf(ReceiverWithSchedules(receiverA, emptyList())), awaitItem())
        }
    }

    @Test
    fun `the first receiver is auto-selected once receivers load and nothing is selected yet`() = runTest {
        viewModel.selectedReceiverId.test {
            assertNull(awaitItem())
            receiversFlow.value = listOf(ReceiverWithSchedules(receiverA, emptyList()), ReceiverWithSchedules(receiverB, emptyList()))
            assertEquals(1L, awaitItem())
        }
    }

    @Test
    fun `history starts null then reflects the currently selected receiver's data`() = runTest {
        every { transmissionRepository.observeSentHistory(1L, any()) } returns flowOf(listOf(recordA))

        viewModel.history.test {
            assertEquals(null, awaitItem())
            receiversFlow.value = listOf(ReceiverWithSchedules(receiverA, emptyList()))
            assertEquals(listOf(recordA), awaitItem())
        }
    }

    @Test
    fun `switching the selected receiver re-subscribes history without leaking the previous receiver's data`() = runTest {
        val recordB = DeliveryRecord(transmissionId = 2L, image = image.copy(id = 20L), sentAt = fixedNow)
        every { transmissionRepository.observeSentHistory(1L, any()) } returns flowOf(listOf(recordA))
        every { transmissionRepository.observeSentHistory(2L, any()) } returns flowOf(listOf(recordB))
        receiversFlow.value = listOf(ReceiverWithSchedules(receiverA, emptyList()), ReceiverWithSchedules(receiverB, emptyList()))

        viewModel.history.test {
            assertNull(awaitItem()) // seed value, before this StateFlow's own upstream starts collecting
            assertEquals(listOf(recordA), awaitItem()) // auto-selected receiverA

            viewModel.onSelectReceiver(2L)

            assertEquals(listOf(recordB), awaitItem())
        }
    }

    @Test
    fun `re-selects the first remaining receiver when the currently-selected one disappears from the list`() = runTest {
        every { transmissionRepository.observeSentHistory(any(), any()) } returns flowOf(emptyList())

        viewModel.selectedReceiverId.test {
            assertNull(awaitItem())
            receiversFlow.value = listOf(ReceiverWithSchedules(receiverA, emptyList()), ReceiverWithSchedules(receiverB, emptyList()))
            assertEquals(1L, awaitItem()) // auto-selected receiverA

            // receiverA deleted (e.g. via the Receivers tab) — only receiverB remains.
            receiversFlow.value = listOf(ReceiverWithSchedules(receiverB, emptyList()))

            assertEquals(2L, awaitItem())
        }
    }

    @Test
    fun `clears the selection when the receiver list becomes empty`() = runTest {
        viewModel.selectedReceiverId.test {
            assertNull(awaitItem())
            receiversFlow.value = listOf(ReceiverWithSchedules(receiverA, emptyList()))
            assertEquals(1L, awaitItem())

            receiversFlow.value = emptyList()

            assertNull(awaitItem())
        }
    }

    @Test
    fun `observeSentHistory is queried with the cutoff from RetentionPolicy, not a hardcoded 30-day window`() = runTest {
        // Deliberately a cutoff that does NOT correspond to 30 days — proves the window comes
        // from RetentionPolicy.observeCutoff(), not a coincidentally-matching literal.
        val distinctCutoff = fixedNow.minus(45, ChronoUnit.DAYS)
        cutoffFlow.value = distinctCutoff
        val sinceSlot = slot<Instant>()
        every { transmissionRepository.observeSentHistory(1L, capture(sinceSlot)) } returns flowOf(emptyList())

        viewModel.history.test {
            awaitItem() // null, no selection yet
            receiversFlow.value = listOf(ReceiverWithSchedules(receiverA, emptyList()))
            awaitItem()
        }

        assertEquals(distinctCutoff, sinceSlot.captured)
    }
}
