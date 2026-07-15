package com.ris.imagedistributor.domain

import com.ris.imagedistributor.data.local.Image
import com.ris.imagedistributor.data.local.Receiver
import com.ris.imagedistributor.data.repository.ImageRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import kotlin.random.Random

class ImageSelectionEngineTest {

    private lateinit var imageRepository: ImageRepository

    private fun activeImages(count: Int): List<Image> =
        (1..count).map { Image(id = it.toLong(), filePath = "$it.jpg", active = true, uploadedAt = 0L) }

    private fun receiver(id: Long, min: Int, max: Int): Receiver =
        Receiver(id = id, name = "R$id", channel = "WHATSAPP", phoneOrEmail = "+91$id", minCount = min, maxCount = max)

    @Before
    fun setUp() {
        imageRepository = mockk()
    }

    @Test
    fun `selectImagesForUnits returns an empty map without querying images when units is empty`() = runTest {
        val engine = ImageSelectionEngine(imageRepository)

        val result = engine.selectImagesForUnits(emptyList())

        assertEquals(AppResult.Success(emptyMap<SelectionUnit, List<Image>>()), result)
        coVerify(exactly = 0) { imageRepository.getActiveImages() }
    }

    @Test
    fun `selectImagesForUnits returns an empty list per unit when there are no active images`() = runTest {
        coEvery { imageRepository.getActiveImages() } returns AppResult.Success(emptyList())
        val units = listOf(SelectionUnit(receiver(1L, 2, 5), 540), SelectionUnit(receiver(2L, 2, 5), 720))
        val engine = ImageSelectionEngine(imageRepository)

        val result = engine.selectImagesForUnits(units)

        assertTrue(result is AppResult.Success)
        val allocation = (result as AppResult.Success).value
        assertEquals(emptyList<Image>(), allocation.getValue(units[0]))
        assertEquals(emptyList<Image>(), allocation.getValue(units[1]))
    }

    @Test
    fun `selectImagesForUnits allocates exactly the active image count across a single unit within its max`() = runTest {
        val active = activeImages(4)
        coEvery { imageRepository.getActiveImages() } returns AppResult.Success(active)
        val unit = SelectionUnit(receiver(1L, min = 2, max = 10), 540)
        val engine = ImageSelectionEngine(imageRepository, random = Random(1))

        val result = engine.selectImagesForUnits(listOf(unit)) as AppResult.Success

        assertEquals(4, result.value.getValue(unit).size)
        assertTrue("every allocated image must come from the active pool", active.containsAll(result.value.getValue(unit)))
    }

    @Test
    fun `selectImagesForUnits caps a single unit at its own max, leaving the rest unallocated`() = runTest {
        val active = activeImages(20)
        coEvery { imageRepository.getActiveImages() } returns AppResult.Success(active)
        val unit = SelectionUnit(receiver(1L, min = 2, max = 5), 540)
        val engine = ImageSelectionEngine(imageRepository, random = Random(1))

        val result = engine.selectImagesForUnits(listOf(unit)) as AppResult.Success

        assertEquals("expected the unit's own max as a hard ceiling", 5, result.value.getValue(unit).size)
    }

    @Test
    fun `selectImagesForUnits sums to the active image count when it fits within every unit's range`() = runTest {
        val active = activeImages(12)
        coEvery { imageRepository.getActiveImages() } returns AppResult.Success(active)
        val units = listOf(
            SelectionUnit(receiver(1L, min = 2, max = 8), 540),
            SelectionUnit(receiver(2L, min = 2, max = 8), 720),
            SelectionUnit(receiver(3L, min = 2, max = 8), 900),
        )
        val engine = ImageSelectionEngine(imageRepository, random = Random(7))

        val result = engine.selectImagesForUnits(units) as AppResult.Success

        val totalAllocated = units.sumOf { result.value.getValue(it).size }
        assertEquals(12, totalAllocated)
        for (unit in units) {
            val count = result.value.getValue(unit).size
            assertTrue("unit ${unit.receiver.id} got $count, expected within [2,8]", count in 2..8)
        }
    }

    @Test
    fun `selectImagesForUnits never repeats an image across two different units`() = runTest {
        val active = activeImages(15)
        coEvery { imageRepository.getActiveImages() } returns AppResult.Success(active)
        val units = listOf(
            SelectionUnit(receiver(1L, min = 3, max = 6), 540),
            SelectionUnit(receiver(2L, min = 3, max = 6), 720),
            SelectionUnit(receiver(3L, min = 3, max = 6), 900),
        )
        val engine = ImageSelectionEngine(imageRepository, random = Random(3))

        val result = engine.selectImagesForUnits(units) as AppResult.Success

        val allIds = units.flatMap { result.value.getValue(it).map(Image::id) }
        assertEquals("expected no image id to repeat across units", allIds.size, allIds.toSet().size)
    }

    @Test
    fun `selectImagesForUnits leaves images unsent when the sum of every max is below the active count`() = runTest {
        val active = activeImages(30)
        coEvery { imageRepository.getActiveImages() } returns AppResult.Success(active)
        val units = listOf(
            SelectionUnit(receiver(1L, min = 2, max = 5), 540),
            SelectionUnit(receiver(2L, min = 2, max = 5), 720),
        )
        val engine = ImageSelectionEngine(imageRepository, random = Random(9))

        val result = engine.selectImagesForUnits(units) as AppResult.Success

        // Sum of maxes (5+5=10) is a hard ceiling — the other 20 active images simply go unsent
        // this round rather than exceeding either unit's max.
        val totalAllocated = units.sumOf { result.value.getValue(it).size }
        assertEquals(10, totalAllocated)
        for (unit in units) assertEquals(5, result.value.getValue(unit).size)
    }

    @Test
    fun `selectImagesForUnits scales below every units minimum when their combined minimums exceed the active count`() = runTest {
        val active = activeImages(4)
        coEvery { imageRepository.getActiveImages() } returns AppResult.Success(active)
        val units = listOf(
            SelectionUnit(receiver(1L, min = 5, max = 10), 540),
            SelectionUnit(receiver(2L, min = 5, max = 10), 720),
        )
        val engine = ImageSelectionEngine(imageRepository, random = Random(11))

        val result = engine.selectImagesForUnits(units) as AppResult.Success

        // Defensive fallback: sum of minimums (10) > active count (4) — normally prevented by
        // ReceiversViewModel.validateMinCountBudget at save time, but this must still degrade
        // safely (never repeat an image, never allocate more than exists) rather than crash.
        val totalAllocated = units.sumOf { result.value.getValue(it).size }
        assertEquals(4, totalAllocated)
        for (unit in units) assertTrue(result.value.getValue(unit).size >= 0)
    }

    @Test
    fun `selectImagesForUnits propagates a Failure from getActiveImages`() = runTest {
        val failure = AppResult.Failure(FailureReason.DATABASE_ERROR)
        coEvery { imageRepository.getActiveImages() } returns failure
        val engine = ImageSelectionEngine(imageRepository)

        val result = engine.selectImagesForUnits(listOf(SelectionUnit(receiver(1L, 2, 5), 540)))

        assertEquals(failure, result)
    }

    @Test
    fun `selectImagesForUnits does not swallow CancellationException`() = runTest {
        coEvery { imageRepository.getActiveImages() } throws CancellationException("cancelled")
        val engine = ImageSelectionEngine(imageRepository)

        try {
            engine.selectImagesForUnits(listOf(SelectionUnit(receiver(1L, 2, 5), 540)))
            fail("expected CancellationException to propagate")
        } catch (e: CancellationException) {
            // expected
        }
    }

    @Test
    fun `selectImagesForUnits with a fixed count range (min equals max) always allocates exactly that count when the pool allows`() = runTest {
        val active = activeImages(6)
        coEvery { imageRepository.getActiveImages() } returns AppResult.Success(active)
        val units = listOf(
            SelectionUnit(receiver(1L, min = 3, max = 3), 540),
            SelectionUnit(receiver(2L, min = 3, max = 3), 720),
        )
        val engine = ImageSelectionEngine(imageRepository, random = Random(5))

        val result = engine.selectImagesForUnits(units) as AppResult.Success

        assertEquals(3, result.value.getValue(units[0]).size)
        assertEquals(3, result.value.getValue(units[1]).size)
    }
}
