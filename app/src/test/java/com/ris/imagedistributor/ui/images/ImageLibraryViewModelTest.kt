package com.ris.imagedistributor.ui.images

import android.net.Uri
import app.cash.turbine.test
import com.ris.imagedistributor.data.local.Image
import com.ris.imagedistributor.data.repository.ImageRepository
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
import org.junit.Before
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class ImageLibraryViewModelTest {

    private lateinit var repository: ImageRepository
    private lateinit var viewModel: ImageLibraryViewModel
    private val imagesFlow = MutableStateFlow<List<Image>>(emptyList())

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        repository = mockk(relaxed = true)
        every { repository.observeImages() } returns imagesFlow
        viewModel = ImageLibraryViewModel(repository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `images reflects the repository flow`() = runTest {
        val image = Image(id = 1, filePath = "a.jpg", active = true, uploadedAt = 1000L)

        viewModel.images.test {
            assertEquals(emptyList<Image>(), awaitItem())
            imagesFlow.value = listOf(image)
            assertEquals(listOf(image), awaitItem())
        }
    }

    @Test
    fun `onImagePicked uploads the picked uri and reports back the uploaded image`() = runTest {
        val uri = mockk<Uri>()
        val uploaded = Image(id = 9L, filePath = "a.jpg", active = true, uploadedAt = 1000L)
        coEvery { repository.uploadImage(uri) } returns AppResult.Success(9L)
        coEvery { repository.getImageById(9L) } returns AppResult.Success(uploaded)
        var reported: Image? = null

        viewModel.onImagePicked(uri) { image -> reported = image }
        testScheduler.advanceUntilIdle()

        coVerify { repository.uploadImage(uri) }
        coVerify { repository.getImageById(9L) }
        assertEquals(uploaded, reported)
    }

    @Test
    fun `onImagePicked with a null uri does not call the repository`() = runTest {
        var called = false

        viewModel.onImagePicked(null) { called = true }
        testScheduler.advanceUntilIdle()

        coVerify(exactly = 0) { repository.uploadImage(any()) }
        assertFalse("expected onResult not to be invoked for a null uri", called)
    }

    @Test
    fun `onImagePicked reports null when the upload fails, without fetching the image`() = runTest {
        val uri = mockk<Uri>()
        coEvery { repository.uploadImage(uri) } returns AppResult.Failure(FailureReason.FILE_ERROR)
        var reported: Image? = Image(id = 1L, filePath = "x.jpg", active = true, uploadedAt = 0L) // sentinel, must become null

        viewModel.onImagePicked(uri) { image -> reported = image }
        testScheduler.advanceUntilIdle()

        coVerify(exactly = 0) { repository.getImageById(any()) }
        assertEquals(null, reported)
    }

    @Test
    fun `onToggleActive delegates to the repository`() = runTest {
        coEvery { repository.setActive(7L, false) } returns AppResult.Success(Unit)

        viewModel.onToggleActive(7L, false)
        testScheduler.advanceUntilIdle()

        coVerify { repository.setActive(7L, false) }
    }

    @Test
    fun `resolveFile delegates to the repository`() {
        val image = Image(id = 1, filePath = "a.jpg", active = true, uploadedAt = 1000L)
        val expected = File("images/a.jpg")
        every { repository.resolveFile("a.jpg") } returns expected

        assertEquals(expected, viewModel.resolveFile(image))
    }

    @Test
    fun `updateImageDetails calls the repository and reports success`() = runTest {
        coEvery { repository.updateImageDetails(1L, "Sunset", "A nice sunset") } returns AppResult.Success(Unit)
        var reported: Boolean? = null

        viewModel.updateImageDetails(1L, "Sunset", "A nice sunset") { success -> reported = success }
        testScheduler.advanceUntilIdle()

        assertEquals(true, reported)
        coVerify { repository.updateImageDetails(1L, "Sunset", "A nice sunset") }
        assertFalse(viewModel.isSaving.value)
    }

    @Test
    fun `updateImageDetails reports failure when the repository call fails`() = runTest {
        coEvery { repository.updateImageDetails(any(), any(), any()) } returns AppResult.Failure(FailureReason.DATABASE_ERROR)
        var reported: Boolean? = null

        viewModel.updateImageDetails(1L, "Sunset", null) { success -> reported = success }
        testScheduler.advanceUntilIdle()

        assertEquals(false, reported)
    }

    @Test
    fun `updateImageDetails ignores a second call while one is already in flight`() = runTest {
        coEvery { repository.updateImageDetails(any(), any(), any()) } returns AppResult.Success(Unit)

        viewModel.updateImageDetails(1L, "First", null) { }
        viewModel.updateImageDetails(1L, "Second", null) { }
        testScheduler.advanceUntilIdle()

        coVerify(exactly = 1) { repository.updateImageDetails(any(), any(), any()) }
    }
}
