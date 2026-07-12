package com.ris.imagedistributor.domain

import com.ris.imagedistributor.data.local.Image
import com.ris.imagedistributor.data.repository.ImageRepository
import com.ris.imagedistributor.data.repository.TransmissionRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.random.Random

class ImageSelectionEngineTest {

    private lateinit var imageRepository: ImageRepository
    private lateinit var transmissionRepository: TransmissionRepository

    private fun activeImages(count: Int): List<Image> =
        (1..count).map { Image(id = it.toLong(), filePath = "$it.jpg", active = true, uploadedAt = 0L) }

    @Before
    fun setUp() {
        imageRepository = mockk()
        transmissionRepository = mockk()
    }

    @Test
    fun `selectImagesFor picks exactly Z images when min equals max and nothing is excluded`() = runTest {
        val active = activeImages(5)
        coEvery { imageRepository.getActiveImages() } returns AppResult.Success(active)
        coEvery { transmissionRepository.getRecentlySentImageIds(any(), any()) } returns AppResult.Success(emptyList())
        val engine = ImageSelectionEngine(imageRepository, transmissionRepository)

        val result = engine.selectImagesFor(receiverId = 1L, minCount = 3, maxCount = 3)

        assertTrue(result is AppResult.Success)
        val selected = (result as AppResult.Success).value
        assertEquals(3, selected.size)
        assertEquals(selected.size, selected.map { it.id }.distinct().size) // no duplicates within the batch
        assertTrue(active.containsAll(selected))
    }

    @Test
    fun `selectImagesFor excludes images sent to the receiver in the last 7 days when the eligible pool still covers Z`() = runTest {
        val active = activeImages(5) // ids 1..5
        coEvery { imageRepository.getActiveImages() } returns AppResult.Success(active)
        coEvery { transmissionRepository.getRecentlySentImageIds(any(), any()) } returns AppResult.Success(listOf(1L, 2L, 3L))
        val engine = ImageSelectionEngine(imageRepository, transmissionRepository)

        // eligible pool = {4, 5}, exactly covers Z=2 -> must return exactly those two, no excluded ids present.
        val result = engine.selectImagesFor(receiverId = 1L, minCount = 2, maxCount = 2)

        assertTrue(result is AppResult.Success)
        val selected = (result as AppResult.Success).value
        assertEquals(setOf(4L, 5L), selected.map { it.id }.toSet())
    }

    @Test
    fun `selectImagesFor falls back to allowing repeats when the eligible pool is smaller than Z`() = runTest {
        val active = activeImages(5) // ids 1..5
        coEvery { imageRepository.getActiveImages() } returns AppResult.Success(active)
        // Only id 5 is eligible (4 of 5 excluded) — smaller than Z=4, must fall back to the full active pool.
        coEvery { transmissionRepository.getRecentlySentImageIds(any(), any()) } returns AppResult.Success(listOf(1L, 2L, 3L, 4L))
        val engine = ImageSelectionEngine(imageRepository, transmissionRepository)

        val result = engine.selectImagesFor(receiverId = 1L, minCount = 4, maxCount = 4)

        assertTrue(result is AppResult.Success)
        val selected = (result as AppResult.Success).value
        assertEquals(4, selected.size)
        assertEquals(selected.size, selected.map { it.id }.distinct().size) // still no duplicates within the batch
        assertTrue(active.containsAll(selected))
    }

    @Test
    fun `selectImagesFor sends only the available active images when the total active count is smaller than Z`() = runTest {
        val active = activeImages(3)
        coEvery { imageRepository.getActiveImages() } returns AppResult.Success(active)
        coEvery { transmissionRepository.getRecentlySentImageIds(any(), any()) } returns AppResult.Success(emptyList())
        val engine = ImageSelectionEngine(imageRepository, transmissionRepository)

        val result = engine.selectImagesFor(receiverId = 1L, minCount = 6, maxCount = 6)

        assertTrue(result is AppResult.Success)
        val selected = (result as AppResult.Success).value
        assertEquals(3, selected.size) // not 6 — no padding/duplication
        assertEquals(active.map { it.id }.toSet(), selected.map { it.id }.toSet())
    }

    @Test
    fun `selectImagesFor re-rolls the random count independently across calls`() = runTest {
        val active = activeImages(10)
        coEvery { imageRepository.getActiveImages() } returns AppResult.Success(active)
        coEvery { transmissionRepository.getRecentlySentImageIds(any(), any()) } returns AppResult.Success(emptyList())
        // A real, fixed-seed Random shared across calls — deterministic (not flaky) but genuinely
        // advances its internal state each draw, proving Z isn't cached/fixed across calls.
        val engine = ImageSelectionEngine(imageRepository, transmissionRepository, random = Random(42))

        val sizes = (1..5).map { engine.selectImagesFor(receiverId = 1L, minCount = 1, maxCount = 10) }
            .map { (it as AppResult.Success).value.size }

        assertTrue("expected varying counts across calls, got $sizes", sizes.toSet().size > 1)
    }

    @Test
    fun `selectImagesFor propagates a Failure from getActiveImages`() = runTest {
        val failure = AppResult.Failure(FailureReason.DATABASE_ERROR)
        coEvery { imageRepository.getActiveImages() } returns failure
        val engine = ImageSelectionEngine(imageRepository, transmissionRepository)

        val result = engine.selectImagesFor(receiverId = 1L, minCount = 1, maxCount = 3)

        assertEquals(failure, result)
    }

    @Test
    fun `selectImagesFor propagates a Failure from getRecentlySentImageIds`() = runTest {
        coEvery { imageRepository.getActiveImages() } returns AppResult.Success(activeImages(5))
        val failure = AppResult.Failure(FailureReason.DATABASE_ERROR)
        coEvery { transmissionRepository.getRecentlySentImageIds(any(), any()) } returns failure
        val engine = ImageSelectionEngine(imageRepository, transmissionRepository)

        val result = engine.selectImagesFor(receiverId = 1L, minCount = 1, maxCount = 3)

        assertEquals(failure, result)
    }

    @Test
    fun `selectImagesFor does not swallow CancellationException`() = runTest {
        coEvery { imageRepository.getActiveImages() } throws CancellationException("cancelled")
        val engine = ImageSelectionEngine(imageRepository, transmissionRepository)

        try {
            engine.selectImagesFor(receiverId = 1L, minCount = 1, maxCount = 3)
            fail("expected CancellationException to propagate")
        } catch (e: CancellationException) {
            // expected
        }
    }

    @Test
    fun `selectImagesFor returns Success with an empty list when there are no active images`() = runTest {
        coEvery { imageRepository.getActiveImages() } returns AppResult.Success(emptyList())
        coEvery { transmissionRepository.getRecentlySentImageIds(any(), any()) } returns AppResult.Success(emptyList())
        val engine = ImageSelectionEngine(imageRepository, transmissionRepository)

        val result = engine.selectImagesFor(receiverId = 1L, minCount = 3, maxCount = 5)

        assertEquals(AppResult.Success(emptyList<Image>()), result)
    }

    @Test
    fun `selectImagesFor rejects minCount greater than maxCount`() = runTest {
        val engine = ImageSelectionEngine(imageRepository, transmissionRepository)

        val result = engine.selectImagesFor(receiverId = 1L, minCount = 5, maxCount = 2)

        assertEquals(AppResult.Failure(FailureReason.INVALID_INPUT), result)
    }

    @Test
    fun `selectImagesFor rejects a negative minCount`() = runTest {
        val engine = ImageSelectionEngine(imageRepository, transmissionRepository)

        val result = engine.selectImagesFor(receiverId = 1L, minCount = -1, maxCount = 3)

        assertEquals(AppResult.Failure(FailureReason.INVALID_INPUT), result)
    }

    @Test
    fun `selectImagesFor rejects maxCount of Int MAX_VALUE to avoid overflow`() = runTest {
        val engine = ImageSelectionEngine(imageRepository, transmissionRepository)

        val result = engine.selectImagesFor(receiverId = 1L, minCount = 1, maxCount = Int.MAX_VALUE)

        assertEquals(AppResult.Failure(FailureReason.INVALID_INPUT), result)
    }

    @Test
    fun `selectImagesFor never draws Z outside the requested min-max bounds`() = runTest {
        val active = activeImages(20)
        coEvery { imageRepository.getActiveImages() } returns AppResult.Success(active)
        coEvery { transmissionRepository.getRecentlySentImageIds(any(), any()) } returns AppResult.Success(emptyList())
        val engine = ImageSelectionEngine(imageRepository, transmissionRepository, random = Random(7))

        repeat(200) {
            val result = engine.selectImagesFor(receiverId = 1L, minCount = 2, maxCount = 8)
            val size = (result as AppResult.Success).value.size
            assertTrue("expected size in [2,8], got $size", size in 2..8)
        }
    }

    @Test
    fun `selectImagesFor queries transmissions since approximately 7 days before now`() = runTest {
        coEvery { imageRepository.getActiveImages() } returns AppResult.Success(activeImages(5))
        val sinceSlot = slot<Instant>()
        coEvery { transmissionRepository.getRecentlySentImageIds(any(), capture(sinceSlot)) } returns AppResult.Success(emptyList())
        val engine = ImageSelectionEngine(imageRepository, transmissionRepository)
        val before = Instant.now()

        engine.selectImagesFor(receiverId = 1L, minCount = 1, maxCount = 3)

        val after = Instant.now()
        val expectedEarliest = before.minus(7, ChronoUnit.DAYS)
        val expectedLatest = after.minus(7, ChronoUnit.DAYS)
        assertTrue(
            "expected $sinceSlot to fall within [$expectedEarliest, $expectedLatest]",
            !sinceSlot.captured.isBefore(expectedEarliest) && !sinceSlot.captured.isAfter(expectedLatest),
        )
        coVerify { transmissionRepository.getRecentlySentImageIds(1L, any()) }
    }
}
