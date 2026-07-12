package com.ris.imagedistributor.ui

/** INSTRUMENTED — see SetupScreenTest.kt header for verification-status context. */
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import com.ris.imagedistributor.data.local.Receiver
import com.ris.imagedistributor.data.local.ReceiverWithSchedules
import com.ris.imagedistributor.data.repository.ReceiverRepository
import com.ris.imagedistributor.ui.receivers.ReceiverEditScreen
import com.ris.imagedistributor.ui.receivers.ReceiversListScreen
import com.ris.imagedistributor.ui.receivers.ReceiversViewModel
import com.ris.imagedistributor.ui.theme.ImageDropTheme
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test

class ReceiversScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun showsEmptyStateWhenNoReceivers() {
        val repository = mockk<ReceiverRepository>(relaxed = true)
        every { repository.observeReceivers() } returns flowOf(emptyList())
        val viewModel = ReceiversViewModel(repository)

        composeTestRule.setContent {
            ImageDropTheme {
                ReceiversListScreen(viewModel = viewModel, onAddNew = {}, onEditReceiver = {})
            }
        }

        composeTestRule.onNodeWithText("No receivers yet — add one to start sending.").assertIsDisplayed()
    }

    @Test
    fun showsReceiverRowWhenOnePresent() {
        val receiver = Receiver(
            id = 1L,
            name = "Asha",
            channel = "WHATSAPP",
            phoneOrEmail = "+911234567890",
            minCount = 2,
            maxCount = 5,
        )
        val entry = ReceiverWithSchedules(receiver, listOf(9 * 60, 12 * 60, 15 * 60, 18 * 60))
        val repository = mockk<ReceiverRepository>(relaxed = true)
        every { repository.observeReceivers() } returns flowOf(listOf(entry))
        val viewModel = ReceiversViewModel(repository)

        composeTestRule.setContent {
            ImageDropTheme {
                ReceiversListScreen(viewModel = viewModel, onAddNew = {}, onEditReceiver = {})
            }
        }

        composeTestRule.onNodeWithText("Asha · WhatsApp").assertIsDisplayed()
        composeTestRule.onNodeWithText("4×/day · 2–5 images").assertIsDisplayed()
    }

    @Test
    fun addReceiverShowsInlineErrorUnderNameFieldWhenBlank() {
        val repository = mockk<ReceiverRepository>(relaxed = true)
        every { repository.observeReceivers() } returns flowOf(emptyList())
        val viewModel = ReceiversViewModel(repository)

        composeTestRule.setContent {
            ImageDropTheme {
                ReceiverEditScreen(
                    viewModel = viewModel,
                    receiverId = null,
                    existing = null,
                    stillLoading = false,
                    onDone = {},
                )
            }
        }

        composeTestRule.onAllNodesWithText("Enter a name.").assertCountEquals(0)
        composeTestRule.onNodeWithText("Save").performClick()
        composeTestRule.onNodeWithText("Enter a name.").assertIsDisplayed()
    }

    @Test
    fun addReceiverShowsInlineErrorWhenFewerThanFourScheduleTimes() {
        val repository = mockk<ReceiverRepository>(relaxed = true)
        every { repository.observeReceivers() } returns flowOf(emptyList())
        val viewModel = ReceiversViewModel(repository)

        composeTestRule.setContent {
            ImageDropTheme {
                ReceiverEditScreen(
                    viewModel = viewModel,
                    receiverId = null,
                    existing = null,
                    stillLoading = false,
                    onDone = {},
                )
            }
        }

        composeTestRule.onAllNodesWithText("Add at least 4 schedule times.").assertCountEquals(0)
        composeTestRule.onNodeWithText("Save").performClick()
        composeTestRule.onNodeWithText("Add at least 4 schedule times.").assertIsDisplayed()
    }

    @Test
    fun channelToggleSwapsContactField() {
        val repository = mockk<ReceiverRepository>(relaxed = true)
        every { repository.observeReceivers() } returns flowOf(emptyList())
        val viewModel = ReceiversViewModel(repository)

        composeTestRule.setContent {
            ImageDropTheme {
                ReceiverEditScreen(
                    viewModel = viewModel,
                    receiverId = null,
                    existing = null,
                    stillLoading = false,
                    onDone = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Phone (+91)").assertIsDisplayed()
        composeTestRule.onNodeWithText("Email").performClick()
        composeTestRule.onAllNodesWithText("Phone (+91)").assertCountEquals(0)
    }
}
