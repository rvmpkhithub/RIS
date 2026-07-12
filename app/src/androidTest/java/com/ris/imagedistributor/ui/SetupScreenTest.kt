package com.ris.imagedistributor.ui

/**
 * INSTRUMENTED — requires a connected device/emulator. Not executed in the sandbox environment
 * this story was implemented in (no Android SDK system-image/emulator was available; see the
 * story's Dev Agent Record for the full verification-status breakdown). Written correctly per
 * Compose UI testing conventions and ready to run in a normal Android Studio / CI setup with a
 * device attached.
 */
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import com.ris.imagedistributor.data.repository.ComplianceRepository
import com.ris.imagedistributor.domain.ComplianceGate
import com.ris.imagedistributor.ui.setup.SetupScreen
import com.ris.imagedistributor.ui.setup.SetupViewModel
import com.ris.imagedistributor.ui.theme.ImageDropTheme
import io.mockk.mockk
import org.junit.Rule
import org.junit.Test

class SetupScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun continueButtonDisabledUntilBothFieldsFilled() {
        val repository = mockk<ComplianceRepository>(relaxed = true)
        val gate = mockk<ComplianceGate>(relaxed = true)
        val viewModel = SetupViewModel(repository, gate)

        composeTestRule.setContent {
            ImageDropTheme {
                SetupScreen(viewModel = viewModel, onProceedToMainApp = {}, onProceedToComplianceHalt = {})
            }
        }

        composeTestRule.onNodeWithText("Continue").assertIsNotEnabled()

        composeTestRule.onNodeWithText("First name / nickname").performTextInput("Arjun")
        composeTestRule.onNodeWithText("City").performTextInput("Pune")

        composeTestRule.onNodeWithText("Continue").assertIsEnabled()
    }
}
