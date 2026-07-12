package com.ris.imagedistributor.ui

/** INSTRUMENTED — see SetupScreenTest.kt header for verification-status context. */
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import com.ris.imagedistributor.data.repository.ImageRepository
import com.ris.imagedistributor.domain.AppResult
import com.ris.imagedistributor.ui.images.ImageLibraryScreen
import com.ris.imagedistributor.ui.images.ImageLibraryViewModel
import com.ris.imagedistributor.ui.theme.ImageDropTheme
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
                ImageLibraryScreen(viewModel = viewModel)
            }
        }

        composeTestRule.onNodeWithText("No images yet — upload some to get started.").assertIsDisplayed()
    }
}
