package com.ris.imagedistributor.ui

/** INSTRUMENTED — see SetupScreenTest.kt header for verification-status context. */
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsDisplayed
import com.ris.imagedistributor.data.local.Image
import com.ris.imagedistributor.data.repository.ImageRepository
import com.ris.imagedistributor.ui.images.ImageLibraryScreen
import com.ris.imagedistributor.ui.images.ImageLibraryViewModel
import com.ris.imagedistributor.ui.theme.ImageDropTheme
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test

class ImageLibraryScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun showsEmptyStateWhenNoImages() {
        val repository = mockk<ImageRepository>(relaxed = true)
        every { repository.observeImages() } returns flowOf(emptyList())
        val viewModel = ImageLibraryViewModel(repository)

        composeTestRule.setContent {
            ImageDropTheme {
                ImageLibraryScreen(viewModel = viewModel, onViewImage = {}, onImageUploaded = {})
            }
        }

        composeTestRule.onNodeWithText("No images yet — upload one to get started.").assertIsDisplayed()
    }

    @Test
    fun showsTitleAndViewButtonNotAThumbnailForAPopulatedList() {
        val titled = Image(id = 1L, filePath = "a.jpg", active = true, uploadedAt = 1000L, title = "Sunset", description = null)
        val untitled = Image(id = 2L, filePath = "b.jpg", active = true, uploadedAt = 2000L)
        val repository = mockk<ImageRepository>(relaxed = true)
        every { repository.observeImages() } returns flowOf(listOf(titled, untitled))
        val viewModel = ImageLibraryViewModel(repository)

        composeTestRule.setContent {
            ImageDropTheme {
                ImageLibraryScreen(viewModel = viewModel, onViewImage = {}, onImageUploaded = {})
            }
        }

        composeTestRule.onNodeWithText("Sunset").assertIsDisplayed()
        composeTestRule.onNodeWithText("Untitled").assertIsDisplayed()
    }

    @Test
    fun tappingViewInvokesTheCallbackWithTheImageId() {
        val image = Image(id = 7L, filePath = "a.jpg", active = true, uploadedAt = 1000L)
        val repository = mockk<ImageRepository>(relaxed = true)
        every { repository.observeImages() } returns flowOf(listOf(image))
        val viewModel = ImageLibraryViewModel(repository)
        var viewedId: Long? = null

        composeTestRule.setContent {
            ImageDropTheme {
                ImageLibraryScreen(viewModel = viewModel, onViewImage = { id -> viewedId = id }, onImageUploaded = {})
            }
        }

        composeTestRule.onNodeWithText("View").performClick()

        assert(viewedId == 7L) { "expected onViewImage to be called with 7L, got $viewedId" }
    }

    @Test
    fun togglingActiveSwitchStillCallsThroughToTheRepository() {
        val image = Image(id = 7L, filePath = "a.jpg", active = true, uploadedAt = 1000L)
        val repository = mockk<ImageRepository>(relaxed = true)
        every { repository.observeImages() } returns flowOf(listOf(image))
        coEvery { repository.setActive(7L, false) } returns com.ris.imagedistributor.domain.AppResult.Success(Unit)
        val viewModel = ImageLibraryViewModel(repository)

        composeTestRule.setContent {
            ImageDropTheme {
                ImageLibraryScreen(viewModel = viewModel, onViewImage = {}, onImageUploaded = {})
            }
        }

        composeTestRule.onNodeWithContentDescription("Toggle active for image 7").performClick()
        composeTestRule.waitForIdle()

        coVerify { repository.setActive(7L, false) }
    }
}
