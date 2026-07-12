package com.ris.imagedistributor.ui

/** INSTRUMENTED — see SetupScreenTest.kt header for verification-status context. */
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.ris.imagedistributor.ui.compliance.ComplianceHaltScreen
import com.ris.imagedistributor.ui.theme.ImageDropTheme
import org.junit.Rule
import org.junit.Test

class ComplianceHaltScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun showsHaltMessageWithNoInteractiveElements() {
        composeTestRule.setContent {
            ImageDropTheme {
                ComplianceHaltScreen()
            }
        }

        composeTestRule.onNodeWithText("Not compliant. Contact admin.").assertIsDisplayed()
    }
}
