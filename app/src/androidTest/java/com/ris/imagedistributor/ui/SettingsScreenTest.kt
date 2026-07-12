package com.ris.imagedistributor.ui

/** INSTRUMENTED — see SetupScreenTest.kt header for verification-status context. */
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.assertIsDisplayed
import com.ris.imagedistributor.data.repository.RetentionRepository
import com.ris.imagedistributor.di.AppContainer
import com.ris.imagedistributor.domain.AppResult
import com.ris.imagedistributor.ui.settings.SettingsScreen
import com.ris.imagedistributor.ui.theme.ImageDropTheme
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test

class SettingsScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun containerWith(initialDays: Int): Pair<AppContainer, RetentionRepository> {
        val daysFlow = MutableStateFlow(initialDays)
        val retentionRepository = mockk<RetentionRepository>(relaxed = true)
        every { retentionRepository.observeRetentionDays() } returns daysFlow
        coEvery { retentionRepository.setRetentionDays(any()) } answers {
            daysFlow.value = firstArg()
            AppResult.Success(Unit)
        }
        val container = mockk<AppContainer>(relaxed = true)
        every { container.retentionRepository } returns retentionRepository
        return container to retentionRepository
    }

    @Test
    fun showsTheCurrentRetentionValue() {
        val (container, _) = containerWith(30)

        composeTestRule.setContent {
            ImageDropTheme { SettingsScreen(container) }
        }

        composeTestRule.onNodeWithText("30 days").assertIsDisplayed()
    }

    @Test
    fun tappingTheRowOpensTheNumericPickerDialog() {
        val (container, _) = containerWith(30)

        composeTestRule.setContent {
            ImageDropTheme { SettingsScreen(container) }
        }

        composeTestRule.onNodeWithText("Retention period").performClick()

        composeTestRule.onNodeWithText("Days").assertIsDisplayed()
    }

    @Test
    fun enteringAnInvalidValueShowsTheInlineErrorAndDoesNotSave() {
        val (container, retentionRepository) = containerWith(30)

        composeTestRule.setContent {
            ImageDropTheme { SettingsScreen(container) }
        }

        composeTestRule.onNodeWithText("Retention period").performClick()
        composeTestRule.onNodeWithText("30").performTextClearance()
        composeTestRule.onNodeWithText("Save").performClick()

        composeTestRule.onNodeWithText("Enter a valid number of days.").assertIsDisplayed()
        coVerify(exactly = 0) { retentionRepository.setRetentionDays(any()) }
    }

    // [Review][Patch] regression test for the stale-error-message bug — the inline error
    // previously stayed on screen while the user typed a correction, until the next Save tap.
    @Test
    fun editingTheFieldAfterAFailedValidationClearsTheInlineErrorImmediately() {
        val (container, _) = containerWith(30)

        composeTestRule.setContent {
            ImageDropTheme { SettingsScreen(container) }
        }

        composeTestRule.onNodeWithText("Retention period").performClick()
        composeTestRule.onNodeWithText("30").performTextClearance()
        composeTestRule.onNodeWithText("Save").performClick()
        composeTestRule.onNodeWithText("Enter a valid number of days.").assertIsDisplayed()

        composeTestRule.onNodeWithText("Days").performTextInput("60")

        composeTestRule.onNodeWithText("Enter a valid number of days.").assertDoesNotExist()
    }

    @Test
    fun enteringAValidValueAndSavingUpdatesTheDisplayedRowValue() {
        val (container, _) = containerWith(30)

        composeTestRule.setContent {
            ImageDropTheme { SettingsScreen(container) }
        }

        composeTestRule.onNodeWithText("Retention period").performClick()
        composeTestRule.onNodeWithText("30").performTextClearance()
        composeTestRule.onNodeWithText("Days").performTextInput("60")
        composeTestRule.onNodeWithText("Save").performClick()

        composeTestRule.onNodeWithText("60 days").assertIsDisplayed()
    }
}
