package com.ris.imagedistributor.data.repository

import android.net.Uri
import com.ris.imagedistributor.data.local.Image
import com.ris.imagedistributor.data.local.ImageDao
import com.ris.imagedistributor.data.local.ImageFileStore
import com.ris.imagedistributor.domain.AppResult
import com.ris.imagedistributor.domain.FailureReason
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException

class ImageRepositoryImplTest {

    private lateinit var dao: ImageDao
    private lateinit var fileStore: ImageFileStore
    private lateinit var repository: ImageRepositoryImpl

    private val uri1: Uri = mockk()
    private val uri2: Uri = mockk()

    @Before
    fun setUp() {
        dao = mockk(relaxed = true)
        fileStore = mockk()
        repository = ImageRepositoryImpl(dao, fileStore)
    }

    @Test
    fun `uploadImages copies then inserts each image in order, never inserting before its copy succeeds`() = runTest {
        coEvery { fileStore.copyToAppStorage(uri1) } returns "a.jpg"
        coEvery { fileStore.copyToAppStorage(uri2) } returns "b.jpg"
        coEvery { dao.insert(any()) } returns 1L

        val result = repository.uploadImages(listOf(uri1, uri2))

        assertTrue(result is AppResult.Success)
        coVerifyOrder {
            fileStore.copyToAppStorage(uri1)
            dao.insert(match { it.filePath == "a.jpg" && it.active })
            fileStore.copyToAppStorage(uri2)
            dao.insert(match { it.filePath == "b.jpg" && it.active })
        }
    }

    @Test
    fun `uploadImages failure on the file copy maps to FILE_ERROR and does not insert a dangling row`() = runTest {
        coEvery { fileStore.copyToAppStorage(uri1) } throws IOException("disk full")

        val result = repository.uploadImages(listOf(uri1))

        assertEquals(AppResult.Failure(FailureReason.FILE_ERROR), result)
        coVerify(exactly = 0) { dao.insert(any()) }
    }

    @Test
    fun `uploadImages failure on the DB insert maps to DATABASE_ERROR and deletes the just-copied file`() = runTest {
        coEvery { fileStore.copyToAppStorage(uri1) } returns "a.jpg"
        coEvery { dao.insert(any()) } throws RuntimeException("boom")
        every { fileStore.delete(any()) } just Runs

        val result = repository.uploadImages(listOf(uri1))

        assertEquals(AppResult.Failure(FailureReason.DATABASE_ERROR), result)
        coVerify { fileStore.delete("a.jpg") }
    }

    // [Review][Patch] regression test for the all-or-nothing batch bug — a failure partway
    // through a multi-image upload used to silently abandon the remaining uris (never attempted)
    // while still keeping whatever had already persisted, yet reported the whole call as Failure.
    @Test
    fun `uploadImages attempts every uri even after an earlier one fails, and still reports the overall failure`() = runTest {
        val uri3: Uri = mockk()
        coEvery { fileStore.copyToAppStorage(uri1) } returns "a.jpg"
        coEvery { fileStore.copyToAppStorage(uri2) } throws IOException("bad file")
        coEvery { fileStore.copyToAppStorage(uri3) } returns "c.jpg"
        coEvery { dao.insert(any()) } returns 1L

        val result = repository.uploadImages(listOf(uri1, uri2, uri3))

        assertEquals(AppResult.Failure(FailureReason.FILE_ERROR), result)
        coVerify { dao.insert(match { it.filePath == "a.jpg" }) }
        coVerify { fileStore.copyToAppStorage(uri3) } // still attempted despite uri2's failure
        coVerify { dao.insert(match { it.filePath == "c.jpg" }) }
    }

    @Test
    fun `uploadImages does not swallow CancellationException`() = runTest {
        coEvery { fileStore.copyToAppStorage(uri1) } throws CancellationException("cancelled")

        try {
            repository.uploadImages(listOf(uri1))
            org.junit.Assert.fail("expected CancellationException to propagate")
        } catch (e: CancellationException) {
            // expected
        }
    }

    @Test
    fun `setActive delegates to the dao`() = runTest {
        val result = repository.setActive(id = 5L, active = false)

        assertTrue(result is AppResult.Success)
        coVerify { dao.setActive(5L, false) }
    }

    @Test
    fun `setActive DB failure maps to DATABASE_ERROR`() = runTest {
        coEvery { dao.setActive(any(), any()) } throws RuntimeException("boom")

        val result = repository.setActive(id = 5L, active = false)

        assertEquals(AppResult.Failure(FailureReason.DATABASE_ERROR), result)
    }

    @Test
    fun `getActiveImages delegates to the dao`() = runTest {
        val active = listOf(Image(id = 1, filePath = "a.jpg", active = true, uploadedAt = 1000L))
        coEvery { dao.getActive() } returns active

        val result = repository.getActiveImages()

        assertEquals(AppResult.Success(active), result)
    }

    @Test
    fun `getActiveImages DB failure maps to DATABASE_ERROR`() = runTest {
        coEvery { dao.getActive() } throws RuntimeException("boom")

        val result = repository.getActiveImages()

        assertEquals(AppResult.Failure(FailureReason.DATABASE_ERROR), result)
    }

    @Test
    fun `getImageById delegates to the dao and returns the image regardless of its active flag`() = runTest {
        val inactive = Image(id = 7L, filePath = "c.jpg", active = false, uploadedAt = 1000L)
        coEvery { dao.getById(7L) } returns inactive

        val result = repository.getImageById(7L)

        assertEquals(AppResult.Success(inactive), result)
    }

    @Test
    fun `getImageById returns Success null when no row matches`() = runTest {
        coEvery { dao.getById(99L) } returns null

        val result = repository.getImageById(99L)

        assertEquals(AppResult.Success(null), result)
    }

    @Test
    fun `getImageById DB failure maps to DATABASE_ERROR`() = runTest {
        coEvery { dao.getById(any()) } throws RuntimeException("boom")

        val result = repository.getImageById(7L)

        assertEquals(AppResult.Failure(FailureReason.DATABASE_ERROR), result)
    }

    @Test
    fun `resolveFile delegates to the file store`() {
        val expected = File("images/a.jpg")
        every { fileStore.absolutePath("a.jpg") } returns expected

        assertEquals(expected, repository.resolveFile("a.jpg"))
    }
}
