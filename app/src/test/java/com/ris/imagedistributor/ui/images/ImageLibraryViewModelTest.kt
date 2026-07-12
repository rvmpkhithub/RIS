package com.ris.imagedistributor.ui.images

import android.net.Uri
import app.cash.turbine.test
import com.ris.imagedistributor.data.local.Image
import com.ris.imagedistributor.data.repository.ImageRepository
import com.ris.imagedistributor.domain.AppResult
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
    fun `onImagesPicked uploads the picked uris`() = runTest {
        val uri = mockk<Uri>()
        coEvery { repository.uploadImages(listOf(uri)) } returns AppResult.Success(Unit)

        viewModel.onImagesPicked(listOf(uri))
        testScheduler.advanceUntilIdle()

        coVerify { repository.uploadImages(listOf(uri)) }
    }

    @Test
    fun `onImagesPicked with an empty list does not call the repository`() = runTest {
        viewModel.onImagesPicked(emptyList())
        testScheduler.advanceUntilIdle()

        coVerify(exactly = 0) { repository.uploadImages(any()) }
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
}
