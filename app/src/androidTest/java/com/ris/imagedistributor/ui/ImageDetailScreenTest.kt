package com.ris.imagedistributor.ui

/** INSTRUMENTED — see SetupScreenTest.kt header for verification-status context. */
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import com.ris.imagedistributor.data.local.Image
import com.ris.imagedistributor.data.repository.ImageRepository
import com.ris.imagedistributor.domain.AppResult
import com.ris.imagedistributor.domain.FailureReason
import com.ris.imagedistributor.ui.images.ImageDetailScreen
import com.ris.imagedistributor.ui.images.ImageLibraryViewModel
import com.ris.imagedistributor.ui.theme.ImageDropTheme
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

class ImageDetailScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val image = Image(id = 1L, filePath = "a.jpg", active = true, uploadedAt = 1000L, title = "Sunset", description = "A nice sunset")

    @Test
    fun showsExistingTitleAndDescriptionPreFilled() {
        val repository = mockk<ImageRepository>(relaxed = true)
        stubResolveFile(repository)
        val viewModel = ImageLibraryViewModel(repository)

        composeTestRule.setContent {
            ImageDropTheme {
                ImageDetailScreen(viewModel = viewModel, imageId = 1L, existing = image, stillLoading = false, onDone = {})
            }
        }

        composeTestRule.onNodeWithText("Sunset").assertIsDisplayed()
        composeTestRule.onNodeWithText("A nice sunset").assertIsDisplayed()
    }

    @Test
    fun editingAndSavingCallsThroughToTheRepositoryAndReturnsOnSuccess() {
        val repository = mockk<ImageRepository>(relaxed = true)
        stubResolveFile(repository)
        coEvery { repository.updateImageDetails(1L, "Sunrise", "A nice sunset") } returns AppResult.Success(Unit)
        val viewModel = ImageLibraryViewModel(repository)
        var done = false

        composeTestRule.setContent {
            ImageDropTheme {
                ImageDetailScreen(viewModel = viewModel, imageId = 1L, existing = image, stillLoading = false, onDone = { done = true })
            }
        }

        composeTestRule.onNodeWithText("Sunset").performTextClearance()
        composeTestRule.onNodeWithText("Title").performTextInput("Sunrise")
        composeTestRule.onNodeWithText("Save").performClick()
        composeTestRule.waitForIdle()

        coVerify { repository.updateImageDetails(1L, "Sunrise", "A nice sunset") }
        assertTrue("expected onDone to fire on a successful save", done)
    }

    @Test
    fun saveFailureShowsInlineErrorAndDoesNotReturn() {
        val repository = mockk<ImageRepository>(relaxed = true)
        stubResolveFile(repository)
        coEvery { repository.updateImageDetails(any(), any(), any()) } returns AppResult.Failure(FailureReason.DATABASE_ERROR)
        val viewModel = ImageLibraryViewModel(repository)
        var done = false

        composeTestRule.setContent {
            ImageDropTheme {
                ImageDetailScreen(viewModel = viewModel, imageId = 1L, existing = image, stillLoading = false, onDone = { done = true })
            }
        }

        composeTestRule.onNodeWithText("Save").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Couldn't save — please try again.").assertIsDisplayed()
        assertFalse("expected onDone not to fire on a failed save", done)
        // [Review][Patch] isSaving must reset to false after a failure so the user can retry —
        // asserted at the UI layer, not just inferred from the ViewModel-level unit test.
        composeTestRule.onNodeWithText("Save").assertIsEnabled()
    }

    @Test
    fun blocksSaveWithBlankTitleWhenTitleIsRequired() {
        val repository = mockk<ImageRepository>(relaxed = true)
        stubResolveFile(repository)
        val viewModel = ImageLibraryViewModel(repository)
        var done = false

        composeTestRule.setContent {
            ImageDropTheme {
                ImageDetailScreen(
                    viewModel = viewModel,
                    imageId = 1L,
                    existing = image,
                    stillLoading = false,
                    requireTitle = true,
                    onDone = { done = true },
                )
            }
        }

        composeTestRule.onNodeWithText("Sunset").performTextClearance()
        composeTestRule.onNodeWithText("Save").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Title is required.").assertIsDisplayed()
        assertFalse("expected onDone not to fire when a required title is blank", done)
        coVerify(exactly = 0) { repository.updateImageDetails(any(), any(), any()) }
    }

    @Test
    fun allowsSaveWithBlankTitleWhenTitleIsNotRequired() {
        val repository = mockk<ImageRepository>(relaxed = true)
        stubResolveFile(repository)
        coEvery { repository.updateImageDetails(1L, "", "A nice sunset") } returns AppResult.Success(Unit)
        val viewModel = ImageLibraryViewModel(repository)
        var done = false

        composeTestRule.setContent {
            ImageDropTheme {
                ImageDetailScreen(
                    viewModel = viewModel,
                    imageId = 1L,
                    existing = image,
                    stillLoading = false,
                    onDone = { done = true },
                )
            }
        }

        composeTestRule.onNodeWithText("Sunset").performTextClearance()
        composeTestRule.onNodeWithText("Save").performClick()
        composeTestRule.waitForIdle()

        assertTrue("expected onDone to fire — title is optional when requireTitle is false (the default)", done)
    }

    private fun stubResolveFile(repository: ImageRepository) {
        every { repository.resolveFile(any()) } returns File("images/a.jpg")
    }
}
