package com.ris.imagedistributor.ui

/** INSTRUMENTED — see SetupScreenTest.kt header for verification-status context. */
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import com.ris.imagedistributor.data.local.Receiver
import com.ris.imagedistributor.data.local.ReceiverWithSchedules
import com.ris.imagedistributor.data.repository.ReceiverRepository
import com.ris.imagedistributor.domain.AppResult
import com.ris.imagedistributor.ui.receivers.ReceiverEditScreen
import com.ris.imagedistributor.ui.receivers.ReceiversListScreen
import com.ris.imagedistributor.ui.receivers.ReceiversViewModel
import com.ris.imagedistributor.ui.theme.ImageDropTheme
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
        composeTestRule.onNodeWithText("4×/day").assertIsDisplayed()
    }

    @Test
    fun showsUsesMasterScheduleWhenReceiverHasNoScheduleOfItsOwn() {
        val receiver = Receiver(id = 1L, name = "Asha", channel = "WHATSAPP", phoneOrEmail = "+911234567890")
        val entry = ReceiverWithSchedules(receiver, emptyList())
        val repository = mockk<ReceiverRepository>(relaxed = true)
        every { repository.observeReceivers() } returns flowOf(listOf(entry))
        val viewModel = ReceiversViewModel(repository)

        composeTestRule.setContent {
            ImageDropTheme {
                ReceiversListScreen(viewModel = viewModel, onAddNew = {}, onEditReceiver = {})
            }
        }

        composeTestRule.onNodeWithText("Uses master schedule").assertIsDisplayed()
        composeTestRule.onAllNodesWithText("Needs 4 more schedule time(s)").assertCountEquals(0)
    }

    // [Review][Patch] a partially-filled schedule (1-3 times, not empty) must still warn on the
    // list row — only the fully-empty case is allowed to fall back to the master schedule silently.
    @Test
    fun showsNeedsMoreScheduleTimesWhenReceiverScheduleIsPartiallyFilled() {
        val receiver = Receiver(id = 1L, name = "Asha", channel = "WHATSAPP", phoneOrEmail = "+911234567890")
        val entry = ReceiverWithSchedules(receiver, listOf(9 * 60, 12 * 60))
        val repository = mockk<ReceiverRepository>(relaxed = true)
        every { repository.observeReceivers() } returns flowOf(listOf(entry))
        val viewModel = ReceiversViewModel(repository)

        composeTestRule.setContent {
            ImageDropTheme {
                ReceiversListScreen(viewModel = viewModel, onAddNew = {}, onEditReceiver = {})
            }
        }

        composeTestRule.onNodeWithText("2×/day").assertIsDisplayed()
        composeTestRule.onNodeWithText("Needs 2 more schedule time(s)").assertIsDisplayed()
        composeTestRule.onAllNodesWithText("Uses master schedule").assertCountEquals(0)
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
    fun addReceiverDoesNotShowScheduleErrorWhenScheduleIsEmpty() {
        // A brand-new form starts with zero schedule times — this is now valid (falls back to
        // the master schedule) rather than an error, per AC4 / [Sprint Change Proposal 2026-07-12].
        val repository = mockk<ReceiverRepository>(relaxed = true)
        every { repository.observeReceivers() } returns flowOf(emptyList())
        coEvery { repository.addReceiver(any(), emptyList()) } returns AppResult.Success(Unit)
        val viewModel = ReceiversViewModel(repository)
        var done = false

        composeTestRule.setContent {
            ImageDropTheme {
                ReceiverEditScreen(
                    viewModel = viewModel,
                    receiverId = null,
                    existing = null,
                    stillLoading = false,
                    onDone = { done = true },
                )
            }
        }

        // [Review][Patch] fill name/phone too — an empty schedule alone isn't enough to prove
        // Save actually succeeded, since a missing name/phone would also block Save with its own
        // error and this test wouldn't have caught that (scheduleError is computed unconditionally
        // regardless of the other fields, so the absent-schedule-error assertion alone passed even
        // when Save was actually blocked by name/contact validation).
        composeTestRule.onNodeWithText("Name").performTextInput("Priya")
        composeTestRule.onNodeWithText("Phone (+91)").performTextInput("9876543210")
        composeTestRule.onNodeWithText("Save").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onAllNodesWithText("Add at least 4 schedule times.").assertCountEquals(0)
        coVerify { repository.addReceiver(any(), emptyList()) }
        assertTrue("expected onDone to fire on a successful save with an empty schedule", done)
    }

    @Test
    fun editReceiverSucceedsWithZeroScheduleTimes() {
        val receiver = Receiver(id = 1L, name = "Asha", channel = "WHATSAPP", phoneOrEmail = "+911234567890")
        val existing = ReceiverWithSchedules(receiver, emptyList())
        val repository = mockk<ReceiverRepository>(relaxed = true)
        every { repository.observeReceivers() } returns flowOf(listOf(existing))
        coEvery { repository.updateReceiver(any(), emptyList()) } returns AppResult.Success(Unit)
        val viewModel = ReceiversViewModel(repository)
        var done = false

        composeTestRule.setContent {
            ImageDropTheme {
                ReceiverEditScreen(
                    viewModel = viewModel,
                    receiverId = 1L,
                    existing = existing,
                    stillLoading = false,
                    onDone = { done = true },
                )
            }
        }

        composeTestRule.onNodeWithText("Save").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onAllNodesWithText("Add at least 4 schedule times.").assertCountEquals(0)
        assertTrue("expected onDone to fire on a successful save with an empty schedule", done)
    }

    @Test
    fun editReceiverBlocksSaveWithPartiallyFilledSchedule() {
        val receiver = Receiver(id = 1L, name = "Asha", channel = "WHATSAPP", phoneOrEmail = "+911234567890")
        // Exactly 2 schedule times — partially filled, still invalid per AC4.
        val existing = ReceiverWithSchedules(receiver, listOf(9 * 60, 12 * 60))
        val repository = mockk<ReceiverRepository>(relaxed = true)
        every { repository.observeReceivers() } returns flowOf(listOf(existing))
        val viewModel = ReceiversViewModel(repository)
        var done = false

        composeTestRule.setContent {
            ImageDropTheme {
                ReceiverEditScreen(
                    viewModel = viewModel,
                    receiverId = 1L,
                    existing = existing,
                    stillLoading = false,
                    onDone = { done = true },
                )
            }
        }

        composeTestRule.onNodeWithText("Save").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Add at least 4 schedule times.").assertIsDisplayed()
        assertFalse("expected onDone not to fire when Save is blocked", done)
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

    @Test
    fun addReceiverFormHasNoMinMaxImageFields() {
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

        composeTestRule.onAllNodesWithText("Min images").assertCountEquals(0)
        composeTestRule.onAllNodesWithText("Max images").assertCountEquals(0)
    }

    @Test
    fun addReceiverSucceedsWithOnlyNameChannelAndContactFilled() {
        val repository = mockk<ReceiverRepository>(relaxed = true)
        every { repository.observeReceivers() } returns flowOf(emptyList())
        coEvery { repository.addReceiver(any(), any()) } returns AppResult.Success(Unit)
        val viewModel = ReceiversViewModel(repository)
        var done = false

        composeTestRule.setContent {
            ImageDropTheme {
                ReceiverEditScreen(
                    viewModel = viewModel,
                    receiverId = null,
                    existing = null,
                    stillLoading = false,
                    onDone = { done = true },
                )
            }
        }

        composeTestRule.onNodeWithText("Name").performTextInput("Priya")
        composeTestRule.onNodeWithText("Phone (+91)").performTextInput("9876543210")
        composeTestRule.onNodeWithText("Save").performClick()
        composeTestRule.waitForIdle()

        assertTrue("expected onDone to fire on a successful save with no count fields to satisfy", done)
    }
}
