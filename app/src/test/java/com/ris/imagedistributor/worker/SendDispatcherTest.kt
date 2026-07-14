package com.ris.imagedistributor.worker

import com.ris.imagedistributor.data.local.ComplianceState
import com.ris.imagedistributor.data.local.Image
import com.ris.imagedistributor.data.local.Receiver
import com.ris.imagedistributor.data.local.ReceiverWithSchedules
import com.ris.imagedistributor.data.local.Transmission
import com.ris.imagedistributor.data.repository.ComplianceRepository
import com.ris.imagedistributor.data.repository.DeliveryRepository
import com.ris.imagedistributor.data.repository.ImageRepository
import com.ris.imagedistributor.data.repository.MasterScheduleRepository
import com.ris.imagedistributor.data.repository.ReceiverRepository
import com.ris.imagedistributor.data.repository.TransmissionRepository
import com.ris.imagedistributor.domain.AppResult
import com.ris.imagedistributor.domain.ComplianceGate
import com.ris.imagedistributor.domain.FailureReason
import com.ris.imagedistributor.domain.GateResult
import com.ris.imagedistributor.domain.ImageSelectionEngine
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

class SendDispatcherTest {

    private lateinit var receiverRepository: ReceiverRepository
    private lateinit var imageRepository: ImageRepository
    private lateinit var imageSelectionEngine: ImageSelectionEngine
    private lateinit var transmissionRepository: TransmissionRepository
    private lateinit var deliveryRepository: DeliveryRepository
    private lateinit var complianceRepository: ComplianceRepository
    private lateinit var complianceGate: ComplianceGate
    private lateinit var masterScheduleRepository: MasterScheduleRepository

    private val compliantState = ComplianceState(nickname = "Ris", city = "Pune", locked = true)
    private val receiver = Receiver(id = 1L, name = "Asha", channel = "WHATSAPP", phoneOrEmail = "+911234567890")
    private val image = Image(id = 10L, filePath = "a.jpg", active = true, uploadedAt = 0L)

    // Fixed "now": 2026-07-11T10:00 local time -> 600 minutes since midnight.
    private val fixedNow: Instant = ZonedDateTime.of(2026, 7, 11, 10, 0, 0, 0, ZoneId.systemDefault()).toInstant()

    @Before
    fun setUp() {
        receiverRepository = mockk()
        imageRepository = mockk()
        imageSelectionEngine = mockk()
        transmissionRepository = mockk()
        deliveryRepository = mockk()
        complianceRepository = mockk()
        complianceGate = mockk()
        masterScheduleRepository = mockk()

        coEvery { complianceRepository.getState() } returns AppResult.Success(compliantState)
        coEvery { complianceGate.evaluate(any(), any()) } returns GateResult.Proceed
        coEvery { transmissionRepository.getRetryCandidates() } returns AppResult.Success(emptyList())
        // Every existing test uses receivers with their own non-empty schedule times, so this
        // default keeps them unaffected — only the new fallback-specific tests override it.
        coEvery { masterScheduleRepository.getScheduleTimes() } returns AppResult.Success(emptyList())
    }

    private fun dispatcher(now: () -> Instant = { fixedNow }) = SendDispatcher(
        receiverRepository, imageRepository, imageSelectionEngine, transmissionRepository,
        deliveryRepository, complianceRepository, complianceGate, masterScheduleRepository, now,
    )

    @Test
    fun `dispatchDueSends skips the entire run when the compliance gate halts`() = runTest {
        coEvery { complianceGate.evaluate(any(), any()) } returns GateResult.Halt

        dispatcher().dispatchDueSends()

        coVerify(exactly = 0) { receiverRepository.getAllWithSchedules() }
        coVerify(exactly = 0) { transmissionRepository.getRetryCandidates() }
        coVerify(exactly = 0) { deliveryRepository.send(any(), any()) }
    }

    @Test
    fun `dispatchDueSends skips the run when no compliance state exists yet`() = runTest {
        coEvery { complianceRepository.getState() } returns AppResult.Success(null)

        dispatcher().dispatchDueSends()

        coVerify(exactly = 0) { receiverRepository.getAllWithSchedules() }
    }

    @Test
    fun `dispatchDueSends skips the run when reading compliance state fails`() = runTest {
        coEvery { complianceRepository.getState() } returns AppResult.Failure(FailureReason.DATABASE_ERROR)

        dispatcher().dispatchDueSends()

        coVerify(exactly = 0) { receiverRepository.getAllWithSchedules() }
    }

    @Test
    fun `a due never-before-dispatched slot selects images, enqueues, and attempts delivery, marking success SENT`() = runTest {
        val rws = ReceiverWithSchedules(receiver, listOf(540)) // 9:00am, due (now is 10:00am)
        coEvery { receiverRepository.getAllWithSchedules() } returns AppResult.Success(listOf(rws))
        coEvery { transmissionRepository.hasDispatchedToday(1L, 540, any()) } returns AppResult.Success(false)
        coEvery { imageSelectionEngine.selectImageFor(1L) } returns AppResult.Success(image)
        val enqueued = Transmission(id = 99L, receiverId = 1L, imageId = 10L, status = "PENDING", attemptCount = 0, sentAt = null, createdAt = fixedNow.toEpochMilli(), scheduleTime = 540)
        coEvery { transmissionRepository.enqueue(1L, 10L, 540, fixedNow) } returns AppResult.Success(enqueued)
        coEvery { deliveryRepository.send(receiver, image) } returns AppResult.Success(Unit)
        coEvery { transmissionRepository.update(any()) } returns AppResult.Success(Unit)

        dispatcher().dispatchDueSends()

        coVerify {
            transmissionRepository.update(
                match { it.status == "SENT" && it.attemptCount == 1 && it.sentAt == fixedNow.toEpochMilli() }
            )
        }
    }

    @Test
    fun `a due slot delivery failure marks the freshly-enqueued row PENDING with attemptCount 1`() = runTest {
        val rws = ReceiverWithSchedules(receiver, listOf(540))
        coEvery { receiverRepository.getAllWithSchedules() } returns AppResult.Success(listOf(rws))
        coEvery { transmissionRepository.hasDispatchedToday(any(), any(), any()) } returns AppResult.Success(false)
        coEvery { imageSelectionEngine.selectImageFor(any()) } returns AppResult.Success(image)
        val enqueued = Transmission(id = 99L, receiverId = 1L, imageId = 10L, status = "PENDING", attemptCount = 0, sentAt = null, createdAt = fixedNow.toEpochMilli(), scheduleTime = 540)
        coEvery { transmissionRepository.enqueue(any(), any(), any(), any()) } returns AppResult.Success(enqueued)
        coEvery { deliveryRepository.send(any(), any()) } returns AppResult.Failure(FailureReason.UNKNOWN)
        coEvery { transmissionRepository.update(any()) } returns AppResult.Success(Unit)

        dispatcher().dispatchDueSends()

        coVerify {
            transmissionRepository.update(match { it.status == "PENDING" && it.attemptCount == 1 })
        }
    }

    @Test
    fun `a slot already dispatched today is not re-selected or re-enqueued`() = runTest {
        val rws = ReceiverWithSchedules(receiver, listOf(540))
        coEvery { receiverRepository.getAllWithSchedules() } returns AppResult.Success(listOf(rws))
        coEvery { transmissionRepository.hasDispatchedToday(1L, 540, any()) } returns AppResult.Success(true)

        dispatcher().dispatchDueSends()

        coVerify(exactly = 0) { imageSelectionEngine.selectImageFor(any()) }
        coVerify(exactly = 0) { transmissionRepository.enqueue(any(), any(), any(), any()) }
    }

    @Test
    fun `a slot whose scheduled time has not arrived yet today is skipped`() = runTest {
        val rws = ReceiverWithSchedules(receiver, listOf(1000)) // later than 10:00am (600 minutes)
        coEvery { receiverRepository.getAllWithSchedules() } returns AppResult.Success(listOf(rws))

        dispatcher().dispatchDueSends()

        coVerify(exactly = 0) { transmissionRepository.hasDispatchedToday(any(), any(), any()) }
        coVerify(exactly = 0) { imageSelectionEngine.selectImageFor(any()) }
    }

    @Test
    fun `retry pass increments attemptCount and stays PENDING when below the cap`() = runTest {
        coEvery { receiverRepository.getAllWithSchedules() } returns AppResult.Success(listOf(ReceiverWithSchedules(receiver, emptyList())))
        val pending = Transmission(id = 5L, receiverId = 1L, imageId = 10L, status = "PENDING", attemptCount = 1, sentAt = null, createdAt = fixedNow.toEpochMilli(), scheduleTime = 540)
        coEvery { transmissionRepository.getRetryCandidates() } returns AppResult.Success(listOf(pending))
        coEvery { imageRepository.getImageById(10L) } returns AppResult.Success(image)
        coEvery { deliveryRepository.send(receiver, image) } returns AppResult.Failure(FailureReason.UNKNOWN)
        coEvery { transmissionRepository.update(any()) } returns AppResult.Success(Unit)

        dispatcher().dispatchDueSends()

        coVerify { transmissionRepository.update(match { it.status == "PENDING" && it.attemptCount == 2 }) }
    }

    @Test
    fun `retry pass marks FAILED terminal once attemptCount reaches MAX_SEND_ATTEMPTS`() = runTest {
        coEvery { receiverRepository.getAllWithSchedules() } returns AppResult.Success(listOf(ReceiverWithSchedules(receiver, emptyList())))
        val pending = Transmission(id = 5L, receiverId = 1L, imageId = 10L, status = "PENDING", attemptCount = 2, sentAt = null, createdAt = fixedNow.toEpochMilli(), scheduleTime = 540)
        coEvery { transmissionRepository.getRetryCandidates() } returns AppResult.Success(listOf(pending))
        coEvery { imageRepository.getImageById(10L) } returns AppResult.Success(image)
        coEvery { deliveryRepository.send(receiver, image) } returns AppResult.Failure(FailureReason.UNKNOWN)
        coEvery { transmissionRepository.update(any()) } returns AppResult.Success(Unit)

        dispatcher().dispatchDueSends()

        coVerify { transmissionRepository.update(match { it.status == "FAILED" && it.attemptCount == 3 }) }
    }

    @Test
    fun `retry pass succeeding marks SENT with sentAt set`() = runTest {
        coEvery { receiverRepository.getAllWithSchedules() } returns AppResult.Success(listOf(ReceiverWithSchedules(receiver, emptyList())))
        val pending = Transmission(id = 5L, receiverId = 1L, imageId = 10L, status = "PENDING", attemptCount = 1, sentAt = null, createdAt = fixedNow.toEpochMilli(), scheduleTime = 540)
        coEvery { transmissionRepository.getRetryCandidates() } returns AppResult.Success(listOf(pending))
        coEvery { imageRepository.getImageById(10L) } returns AppResult.Success(image)
        coEvery { deliveryRepository.send(receiver, image) } returns AppResult.Success(Unit)
        coEvery { transmissionRepository.update(any()) } returns AppResult.Success(Unit)

        dispatcher().dispatchDueSends()

        coVerify {
            transmissionRepository.update(match { it.status == "SENT" && it.attemptCount == 2 && it.sentAt == fixedNow.toEpochMilli() })
        }
    }

    @Test
    fun `retry pass terminates a PENDING item that crossed a day boundary instead of retrying it`() = runTest {
        coEvery { receiverRepository.getAllWithSchedules() } returns AppResult.Success(listOf(ReceiverWithSchedules(receiver, emptyList())))
        val yesterday = fixedNow.minusSeconds(24 * 60 * 60)
        val stalePending = Transmission(id = 5L, receiverId = 1L, imageId = 10L, status = "PENDING", attemptCount = 1, sentAt = null, createdAt = yesterday.toEpochMilli(), scheduleTime = 540)
        coEvery { transmissionRepository.getRetryCandidates() } returns AppResult.Success(listOf(stalePending))
        coEvery { transmissionRepository.update(any()) } returns AppResult.Success(Unit)

        dispatcher().dispatchDueSends()

        coVerify { transmissionRepository.update(match { it.id == 5L && it.status == "FAILED" }) }
        coVerify(exactly = 0) { deliveryRepository.send(any(), any()) }
        coVerify(exactly = 0) { imageRepository.getImageById(any()) }
    }

    @Test
    fun `no-backfill- dispatching on a later day never queries or reasons about an earlier missed day`() = runTest {
        // "Day 2" now, well past a 9am schedule, with no Transmission at all yet for today.
        val dayTwoNow = fixedNow.plusSeconds(24 * 60 * 60)
        val rws = ReceiverWithSchedules(receiver, listOf(540))
        coEvery { receiverRepository.getAllWithSchedules() } returns AppResult.Success(listOf(rws))
        coEvery { transmissionRepository.hasDispatchedToday(1L, 540, any()) } returns AppResult.Success(false)
        coEvery { imageSelectionEngine.selectImageFor(any()) } returns AppResult.Success(image)
        val enqueued = Transmission(id = 1L, receiverId = 1L, imageId = 10L, status = "PENDING", attemptCount = 0, sentAt = null, createdAt = dayTwoNow.toEpochMilli(), scheduleTime = 540)
        coEvery { transmissionRepository.enqueue(any(), any(), any(), any()) } returns AppResult.Success(enqueued)
        coEvery { deliveryRepository.send(any(), any()) } returns AppResult.Success(Unit)
        coEvery { transmissionRepository.update(any()) } returns AppResult.Success(Unit)

        dispatcher(now = { dayTwoNow }).dispatchDueSends()

        // Dispatches normally for "today" (day 2) — there is no method shaped like "get missed
        // days" for the dispatcher to have called in the first place; its literal absence from
        // this mock setup (nothing beyond hasDispatchedToday/enqueue/send/update was ever stubbed
        // or needed) is the proof, not a special-cased skip that could be forgotten.
        coVerify { transmissionRepository.enqueue(1L, 10L, 540, dayTwoNow) }
        coVerify { transmissionRepository.hasDispatchedToday(1L, 540, any()) }
    }

    @Test
    fun `a receiver-lookup failure at the start of a run throws so SendWorker can retry`() = runTest {
        coEvery { receiverRepository.getAllWithSchedules() } returns AppResult.Failure(FailureReason.DATABASE_ERROR)

        try {
            dispatcher().dispatchDueSends()
            fail("expected SendDispatchException to be thrown")
        } catch (e: SendDispatchException) {
            // expected
        }
    }

    @Test
    fun `a retry-candidate lookup failure aborts the retry pass cleanly without throwing`() = runTest {
        coEvery { receiverRepository.getAllWithSchedules() } returns AppResult.Success(emptyList())
        coEvery { transmissionRepository.getRetryCandidates() } returns AppResult.Failure(FailureReason.DATABASE_ERROR)

        dispatcher().dispatchDueSends() // should not throw
    }

    @Test
    fun `CancellationException from the compliance gate propagates`() = runTest {
        coEvery { complianceGate.evaluate(any(), any()) } throws CancellationException("cancelled")

        try {
            dispatcher().dispatchDueSends()
            fail("expected CancellationException to propagate")
        } catch (e: CancellationException) {
            // expected
        }
    }

    @Test
    fun `CancellationException from delivery propagates`() = runTest {
        val rws = ReceiverWithSchedules(receiver, listOf(540))
        coEvery { receiverRepository.getAllWithSchedules() } returns AppResult.Success(listOf(rws))
        coEvery { transmissionRepository.hasDispatchedToday(any(), any(), any()) } returns AppResult.Success(false)
        coEvery { imageSelectionEngine.selectImageFor(any()) } returns AppResult.Success(image)
        val enqueued = Transmission(id = 1L, receiverId = 1L, imageId = 10L, status = "PENDING", attemptCount = 0, sentAt = null, createdAt = 0L, scheduleTime = 540)
        coEvery { transmissionRepository.enqueue(any(), any(), any(), any()) } returns AppResult.Success(enqueued)
        coEvery { deliveryRepository.send(any(), any()) } throws CancellationException("cancelled")

        try {
            dispatcher().dispatchDueSends()
            fail("expected CancellationException to propagate")
        } catch (e: CancellationException) {
            // expected
        }
    }

    @Test
    fun `a receiver with no schedule of its own dispatches using a due master-schedule time`() = runTest {
        val rws = ReceiverWithSchedules(receiver, emptyList())
        coEvery { receiverRepository.getAllWithSchedules() } returns AppResult.Success(listOf(rws))
        coEvery { masterScheduleRepository.getScheduleTimes() } returns AppResult.Success(listOf(540)) // 9:00am, due
        coEvery { transmissionRepository.hasDispatchedToday(1L, 540, any()) } returns AppResult.Success(false)
        coEvery { imageSelectionEngine.selectImageFor(1L) } returns AppResult.Success(image)
        val enqueued = Transmission(id = 1L, receiverId = 1L, imageId = 10L, status = "PENDING", attemptCount = 0, sentAt = null, createdAt = fixedNow.toEpochMilli(), scheduleTime = 540)
        coEvery { transmissionRepository.enqueue(1L, 10L, 540, fixedNow) } returns AppResult.Success(enqueued)
        coEvery { deliveryRepository.send(receiver, image) } returns AppResult.Success(Unit)
        coEvery { transmissionRepository.update(any()) } returns AppResult.Success(Unit)

        dispatcher().dispatchDueSends()

        coVerify { transmissionRepository.enqueue(1L, 10L, 540, fixedNow) }
    }

    @Test
    fun `a receiver with its own schedule never falls back to the master schedule, even partially`() = runTest {
        // Receiver has exactly one time of its own (540, due); the master schedule separately has
        // a different due time (600) that must never be consulted for this receiver at all.
        val rws = ReceiverWithSchedules(receiver, listOf(540))
        coEvery { receiverRepository.getAllWithSchedules() } returns AppResult.Success(listOf(rws))
        coEvery { masterScheduleRepository.getScheduleTimes() } returns AppResult.Success(listOf(600))
        coEvery { transmissionRepository.hasDispatchedToday(1L, 540, any()) } returns AppResult.Success(false)
        coEvery { imageSelectionEngine.selectImageFor(1L) } returns AppResult.Success(image)
        val enqueued = Transmission(id = 1L, receiverId = 1L, imageId = 10L, status = "PENDING", attemptCount = 0, sentAt = null, createdAt = fixedNow.toEpochMilli(), scheduleTime = 540)
        coEvery { transmissionRepository.enqueue(1L, 10L, 540, fixedNow) } returns AppResult.Success(enqueued)
        coEvery { deliveryRepository.send(receiver, image) } returns AppResult.Success(Unit)
        coEvery { transmissionRepository.update(any()) } returns AppResult.Success(Unit)

        dispatcher().dispatchDueSends()

        coVerify { transmissionRepository.enqueue(1L, 10L, 540, fixedNow) }
        coVerify(exactly = 0) { transmissionRepository.hasDispatchedToday(1L, 600, any()) }
        coVerify(exactly = 0) { transmissionRepository.enqueue(1L, any(), 600, any()) }
    }

    @Test
    fun `a master-schedule lookup failure leaves schedule-less receivers with nothing dispatched, without throwing`() = runTest {
        val rws = ReceiverWithSchedules(receiver, emptyList())
        coEvery { receiverRepository.getAllWithSchedules() } returns AppResult.Success(listOf(rws))
        coEvery { masterScheduleRepository.getScheduleTimes() } returns AppResult.Failure(FailureReason.DATABASE_ERROR)

        dispatcher().dispatchDueSends() // should not throw

        coVerify(exactly = 0) { imageSelectionEngine.selectImageFor(any()) }
        coVerify(exactly = 0) { transmissionRepository.enqueue(any(), any(), any(), any()) }
    }

    @Test
    fun `selectImageFor returning null (no active images) results in nothing enqueued and no delivery attempt`() = runTest {
        val rws = ReceiverWithSchedules(receiver, listOf(540))
        coEvery { receiverRepository.getAllWithSchedules() } returns AppResult.Success(listOf(rws))
        coEvery { transmissionRepository.hasDispatchedToday(1L, 540, any()) } returns AppResult.Success(false)
        coEvery { imageSelectionEngine.selectImageFor(1L) } returns AppResult.Success(null)

        dispatcher().dispatchDueSends() // should not throw

        coVerify(exactly = 0) { transmissionRepository.enqueue(any(), any(), any(), any()) }
        coVerify(exactly = 0) { deliveryRepository.send(any(), any()) }
    }
}
