package com.ris.imagedistributor.data.repository

import app.cash.turbine.test
import com.ris.imagedistributor.data.local.DeliveryRecord
import com.ris.imagedistributor.data.local.Image
import com.ris.imagedistributor.data.local.Transmission
import com.ris.imagedistributor.data.local.TransmissionDao
import com.ris.imagedistributor.data.local.TransmissionStatus
import com.ris.imagedistributor.data.local.TransmissionWithImage
import com.ris.imagedistributor.domain.AppResult
import com.ris.imagedistributor.domain.FailureReason
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.time.Instant

class TransmissionRepositoryImplTest {

    private lateinit var dao: TransmissionDao
    private lateinit var repository: TransmissionRepositoryImpl

    @Before
    fun setUp() {
        dao = mockk()
        repository = TransmissionRepositoryImpl(dao)
    }

    @Test
    fun `getRecentlySentImageIds converts Instant to epoch-millis and delegates to the dao with the SENT status`() = runTest {
        val since = Instant.ofEpochMilli(123_456L)
        coEvery { dao.getSentImageIdsSince(5L, "SENT", 123_456L) } returns listOf(10L, 20L)

        val result = repository.getRecentlySentImageIds(5L, since)

        assertEquals(AppResult.Success(listOf(10L, 20L)), result)
        coVerify { dao.getSentImageIdsSince(5L, TransmissionStatus.SENT.name, 123_456L) }
    }

    @Test
    fun `getRecentlySentImageIds DB failure maps to DATABASE_ERROR`() = runTest {
        coEvery { dao.getSentImageIdsSince(any(), any(), any()) } throws RuntimeException("boom")

        val result = repository.getRecentlySentImageIds(5L, Instant.now())

        assertEquals(AppResult.Failure(FailureReason.DATABASE_ERROR), result)
    }

    @Test
    fun `getRecentlySentImageIds does not swallow CancellationException`() = runTest {
        coEvery { dao.getSentImageIdsSince(any(), any(), any()) } throws CancellationException("cancelled")

        try {
            repository.getRecentlySentImageIds(5L, Instant.now())
            fail("expected CancellationException to propagate")
        } catch (e: CancellationException) {
            // expected
        }
    }

    @Test
    fun `enqueue inserts a PENDING row with attemptCount 0 and the given scheduleTime, returning it with its new id`() = runTest {
        val queuedAt = Instant.ofEpochMilli(500_000L)
        val inserted = slot<Transmission>()
        coEvery { dao.insert(capture(inserted)) } returns 42L

        val result = repository.enqueue(receiverId = 5L, imageId = 9L, scheduleTime = 540, queuedAt = queuedAt)

        val expected = Transmission(
            id = 42L,
            receiverId = 5L,
            imageId = 9L,
            status = "PENDING",
            attemptCount = 0,
            sentAt = null,
            createdAt = 500_000L,
            scheduleTime = 540,
        )
        assertEquals(AppResult.Success(expected), result)
        assertEquals(0L, inserted.captured.id) // pre-insert row has the default unassigned id
        assertEquals("PENDING", inserted.captured.status)
    }

    @Test
    fun `enqueue DB failure maps to DATABASE_ERROR`() = runTest {
        coEvery { dao.insert(any()) } throws RuntimeException("boom")

        val result = repository.enqueue(receiverId = 5L, imageId = 9L, scheduleTime = 540, queuedAt = Instant.now())

        assertEquals(AppResult.Failure(FailureReason.DATABASE_ERROR), result)
    }

    @Test
    fun `update delegates the given transmission to the dao`() = runTest {
        val transmission = Transmission(
            id = 1L, receiverId = 5L, imageId = 9L, status = "SENT",
            attemptCount = 1, sentAt = 999L, createdAt = 500L, scheduleTime = 540,
        )
        coEvery { dao.update(transmission) } returns Unit

        val result = repository.update(transmission)

        assertEquals(AppResult.Success(Unit), result)
        coVerify { dao.update(transmission) }
    }

    @Test
    fun `update DB failure maps to DATABASE_ERROR`() = runTest {
        coEvery { dao.update(any()) } throws RuntimeException("boom")

        val result = repository.update(
            Transmission(id = 1L, receiverId = 5L, imageId = 9L, status = "SENT", attemptCount = 1, sentAt = 999L, createdAt = 500L, scheduleTime = 540)
        )

        assertEquals(AppResult.Failure(FailureReason.DATABASE_ERROR), result)
    }

    @Test
    fun `getRetryCandidates queries PENDING rows below the MAX_SEND_ATTEMPTS cap`() = runTest {
        val candidates = listOf(
            Transmission(id = 1L, receiverId = 5L, imageId = 9L, status = "PENDING", attemptCount = 1, sentAt = null, createdAt = 500L, scheduleTime = 540),
        )
        coEvery { dao.getRetryCandidates("PENDING", TransmissionRepository.MAX_SEND_ATTEMPTS) } returns candidates

        val result = repository.getRetryCandidates()

        assertEquals(AppResult.Success(candidates), result)
    }

    @Test
    fun `getRetryCandidates DB failure maps to DATABASE_ERROR`() = runTest {
        coEvery { dao.getRetryCandidates(any(), any()) } throws RuntimeException("boom")

        val result = repository.getRetryCandidates()

        assertEquals(AppResult.Failure(FailureReason.DATABASE_ERROR), result)
    }

    @Test
    fun `hasDispatchedToday returns true when the dao reports at least one matching row`() = runTest {
        val todayStart = Instant.ofEpochMilli(1_000_000L)
        coEvery { dao.countDispatchedSince(5L, 540, 1_000_000L) } returns 2

        val result = repository.hasDispatchedToday(receiverId = 5L, scheduleTime = 540, todayStart = todayStart)

        assertEquals(AppResult.Success(true), result)
    }

    @Test
    fun `hasDispatchedToday returns false when the dao reports zero matching rows`() = runTest {
        coEvery { dao.countDispatchedSince(any(), any(), any()) } returns 0

        val result = repository.hasDispatchedToday(receiverId = 5L, scheduleTime = 540, todayStart = Instant.now())

        assertTrue(result is AppResult.Success)
        assertFalse((result as AppResult.Success).value)
    }

    @Test
    fun `hasDispatchedToday DB failure maps to DATABASE_ERROR`() = runTest {
        coEvery { dao.countDispatchedSince(any(), any(), any()) } throws RuntimeException("boom")

        val result = repository.hasDispatchedToday(receiverId = 5L, scheduleTime = 540, todayStart = Instant.now())

        assertEquals(AppResult.Failure(FailureReason.DATABASE_ERROR), result)
    }

    @Test
    fun `observeSentHistory maps TransmissionWithImage rows to DeliveryRecords using the SENT status`() = runTest {
        val since = Instant.ofEpochMilli(123_456L)
        val image = Image(id = 9L, filePath = "a.jpg", active = true, uploadedAt = 0L)
        val row = TransmissionWithImage(
            transmission = Transmission(id = 1L, receiverId = 5L, imageId = 9L, status = "SENT", attemptCount = 1, sentAt = 999_000L, createdAt = 900_000L, scheduleTime = 540),
            image = image,
        )
        every { dao.observeSentHistory(5L, "SENT", 123_456L) } returns flowOf(listOf(row))

        repository.observeSentHistory(5L, since).test {
            assertEquals(listOf(DeliveryRecord(transmissionId = 1L, image = image, sentAt = Instant.ofEpochMilli(999_000L))), awaitItem())
            awaitComplete()
        }
        coVerify { dao.observeSentHistory(5L, TransmissionStatus.SENT.name, 123_456L) }
    }

    @Test
    fun `observeSentHistory drops a row whose image relation did not resolve`() = runTest {
        val row = TransmissionWithImage(
            transmission = Transmission(id = 1L, receiverId = 5L, imageId = 9L, status = "SENT", attemptCount = 1, sentAt = 999_000L, createdAt = 900_000L, scheduleTime = 540),
            image = null,
        )
        every { dao.observeSentHistory(any(), any(), any()) } returns flowOf(listOf(row))

        repository.observeSentHistory(5L, Instant.now()).test {
            assertEquals(emptyList<DeliveryRecord>(), awaitItem())
            awaitComplete()
        }
    }

    @Test
    fun `observeSentHistory drops a row whose sentAt is unexpectedly null instead of crashing`() = runTest {
        // Defensive: sentAt should always be set on a SENT row (AD-13), but if that invariant is
        // ever violated, this must not take down the whole list with an NPE.
        val row = TransmissionWithImage(
            transmission = Transmission(id = 1L, receiverId = 5L, imageId = 9L, status = "SENT", attemptCount = 1, sentAt = null, createdAt = 900_000L, scheduleTime = 540),
            image = Image(id = 9L, filePath = "a.jpg", active = true, uploadedAt = 0L),
        )
        every { dao.observeSentHistory(any(), any(), any()) } returns flowOf(listOf(row))

        repository.observeSentHistory(5L, Instant.now()).test {
            assertEquals(emptyList<DeliveryRecord>(), awaitItem())
            awaitComplete()
        }
    }

    @Test
    fun `observeSentHistory emits an empty list on a DAO error instead of throwing`() = runTest {
        every { dao.observeSentHistory(any(), any(), any()) } returns flow { throw RuntimeException("boom") }

        repository.observeSentHistory(5L, Instant.now()).test {
            assertEquals(emptyList<DeliveryRecord>(), awaitItem())
            awaitComplete()
        }
    }

    @Test
    fun `observeSentHistory does not swallow CancellationException`() = runTest {
        every { dao.observeSentHistory(any(), any(), any()) } returns flow { throw CancellationException("cancelled") }

        try {
            repository.observeSentHistory(5L, Instant.now()).collect { }
            fail("expected CancellationException to propagate")
        } catch (e: CancellationException) {
            // expected
        }
    }

    @Test
    fun `purgeOlderThan converts the cutoff Instant to epoch-millis and returns the deleted row count`() = runTest {
        val cutoff = Instant.ofEpochMilli(123_456L)
        coEvery { dao.deleteOlderThan(123_456L) } returns 7

        val result = repository.purgeOlderThan(cutoff)

        assertEquals(AppResult.Success(7), result)
        coVerify { dao.deleteOlderThan(123_456L) }
    }

    @Test
    fun `purgeOlderThan DB failure maps to DATABASE_ERROR`() = runTest {
        coEvery { dao.deleteOlderThan(any()) } throws RuntimeException("boom")

        val result = repository.purgeOlderThan(Instant.now())

        assertEquals(AppResult.Failure(FailureReason.DATABASE_ERROR), result)
    }
}
