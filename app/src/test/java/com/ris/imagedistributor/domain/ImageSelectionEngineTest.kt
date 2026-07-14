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
    fun `selectImageFor returns the only eligible image`() = runTest {
        val active = activeImages(3) // ids 1..3
        coEvery { imageRepository.getActiveImages() } returns AppResult.Success(active)
        // ids 1,2 excluded — only id 3 remains eligible.
        coEvery { transmissionRepository.getRecentlySentImageIds(any(), any()) } returns AppResult.Success(listOf(1L, 2L))
        val engine = ImageSelectionEngine(imageRepository, transmissionRepository)

        val result = engine.selectImageFor(receiverId = 1L)

        assertEquals(AppResult.Success(active.single { it.id == 3L }), result)
    }

    @Test
    fun `selectImageFor picks from the eligible pool when it has several candidates`() = runTest {
        val active = activeImages(10) // ids 1..10
        coEvery { imageRepository.getActiveImages() } returns AppResult.Success(active)
        coEvery { transmissionRepository.getRecentlySentImageIds(any(), any()) } returns AppResult.Success(emptyList())
        val engine = ImageSelectionEngine(imageRepository, transmissionRepository, random = Random(42))

        val result = engine.selectImageFor(receiverId = 1L)

        assertTrue(result is AppResult.Success)
        val selected = (result as AppResult.Success).value
        assertTrue("expected a non-null image", selected != null)
        assertTrue("expected the selected image to be a member of the active pool", active.contains(selected))
    }

    @Test
    fun `selectImageFor falls back to a repeat from the full active pool when the eligible pool is empty`() = runTest {
        val active = activeImages(3) // ids 1..3
        coEvery { imageRepository.getActiveImages() } returns AppResult.Success(active)
        // Every active image already sent within 7 days — eligible pool is empty.
        coEvery { transmissionRepository.getRecentlySentImageIds(any(), any()) } returns AppResult.Success(listOf(1L, 2L, 3L))
        val engine = ImageSelectionEngine(imageRepository, transmissionRepository)

        val result = engine.selectImageFor(receiverId = 1L)

        assertTrue(result is AppResult.Success)
        val selected = (result as AppResult.Success).value
        assertTrue("expected a repeat pick from the active pool, not null", selected != null)
        assertTrue("expected the repeat pick to be a member of the active pool", active.contains(selected))
    }

    @Test
    fun `selectImageFor returns Success with null when there are no active images, without querying transmissions`() = runTest {
        coEvery { imageRepository.getActiveImages() } returns AppResult.Success(emptyList())
        val engine = ImageSelectionEngine(imageRepository, transmissionRepository)

        val result = engine.selectImageFor(receiverId = 1L)

        assertEquals(AppResult.Success(null), result)
        coVerify(exactly = 0) { transmissionRepository.getRecentlySentImageIds(any(), any()) }
    }

    @Test
    fun `selectImageFor propagates a Failure from getActiveImages`() = runTest {
        val failure = AppResult.Failure(FailureReason.DATABASE_ERROR)
        coEvery { imageRepository.getActiveImages() } returns failure
        val engine = ImageSelectionEngine(imageRepository, transmissionRepository)

        val result = engine.selectImageFor(receiverId = 1L)

        assertEquals(failure, result)
    }

    @Test
    fun `selectImageFor propagates a Failure from getRecentlySentImageIds`() = runTest {
        coEvery { imageRepository.getActiveImages() } returns AppResult.Success(activeImages(5))
        val failure = AppResult.Failure(FailureReason.DATABASE_ERROR)
        coEvery { transmissionRepository.getRecentlySentImageIds(any(), any()) } returns failure
        val engine = ImageSelectionEngine(imageRepository, transmissionRepository)

        val result = engine.selectImageFor(receiverId = 1L)

        assertEquals(failure, result)
    }

    @Test
    fun `selectImageFor does not swallow CancellationException`() = runTest {
        coEvery { imageRepository.getActiveImages() } throws CancellationException("cancelled")
        val engine = ImageSelectionEngine(imageRepository, transmissionRepository)

        try {
            engine.selectImageFor(receiverId = 1L)
            fail("expected CancellationException to propagate")
        } catch (e: CancellationException) {
            // expected
        }
    }

    @Test
    fun `selectImageFor queries transmissions since approximately 7 days before now`() = runTest {
        coEvery { imageRepository.getActiveImages() } returns AppResult.Success(activeImages(5))
        val sinceSlot = slot<Instant>()
        coEvery { transmissionRepository.getRecentlySentImageIds(any(), capture(sinceSlot)) } returns AppResult.Success(emptyList())
        val engine = ImageSelectionEngine(imageRepository, transmissionRepository)
        val before = Instant.now()

        engine.selectImageFor(receiverId = 1L)

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
