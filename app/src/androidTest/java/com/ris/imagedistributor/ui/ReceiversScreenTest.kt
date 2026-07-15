package com.ris.imagedistributor.ui

/** INSTRUMENTED — see SetupScreenTest.kt header for verification-status context. */
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import com.ris.imagedistributor.data.local.Image
import com.ris.imagedistributor.data.local.Receiver
import com.ris.imagedistributor.data.local.ReceiverWithSchedules
import com.ris.imagedistributor.data.repository.ImageRepository
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

    private fun activeImages(count: Int): List<Image> =
        (1..count).map { Image(id = it.toLong(), filePath = "$it.jpg", active = true, uploadedAt = 0L) }

    /** [activeImageCount] defaults generously high (100) so tests unrelated to the min-count
     * budget validation don't need to think about it — only the tests that specifically exercise
     * that validation pass a tighter count. */
    private fun buildViewModel(repository: ReceiverRepository, activeImageCount: Int = 100): ReceiversViewModel {
        val imageRepository = mockk<ImageRepository>(relaxed = true)
        coEvery { imageRepository.getActiveImages() } returns AppResult.Success(activeImages(activeImageCount))
        return ReceiversViewModel(repository, imageRepository)
    }

    @Test
    fun showsEmptyStateWhenNoReceivers() {
        val repository = mockk<ReceiverRepository>(relaxed = true)
        every { repository.observeReceivers() } returns flowOf(emptyList())
        val viewModel = buildViewModel(repository)

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
        val viewModel = buildViewModel(repository)

        composeTestRule.setContent {
            ImageDropTheme {
                ReceiversListScreen(viewModel = viewModel, onAddNew = {}, onEditReceiver = {})
            }
        }

        composeTestRule.onNodeWithText("Asha · WhatsApp").assertIsDisplayed()
        composeTestRule.onNodeWithText("4×/day · 2–5 images").assertIsDisplayed()
    }

    @Test
    fun showsUsesMasterScheduleWhenReceiverHasNoScheduleOfItsOwn() {
        val receiver = Receiver(id = 1L, name = "Asha", channel = "WHATSAPP", phoneOrEmail = "+911234567890")
        val entry = ReceiverWithSchedules(receiver, emptyList())
        val repository = mockk<ReceiverRepository>(relaxed = true)
        every { repository.observeReceivers() } returns flowOf(listOf(entry))
        val viewModel = buildViewModel(repository)

        composeTestRule.setContent {
            ImageDropTheme {
                ReceiversListScreen(viewModel = viewModel, onAddNew = {}, onEditReceiver = {})
            }
        }

        composeTestRule.onNodeWithText("Uses master schedule · 2–5 images").assertIsDisplayed()
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
        val viewModel = buildViewModel(repository)

        composeTestRule.setContent {
            ImageDropTheme {
                ReceiversListScreen(viewModel = viewModel, onAddNew = {}, onEditReceiver = {})
            }
        }

        composeTestRule.onNodeWithText("2×/day · 2–5 images").assertIsDisplayed()
        composeTestRule.onNodeWithText("Needs 2 more schedule time(s)").assertIsDisplayed()
        composeTestRule.onAllNodesWithText("Uses master schedule · 2–5 images").assertCountEquals(0)
    }

    @Test
    fun addReceiverShowsInlineErrorUnderNameFieldWhenBlank() {
        val repository = mockk<ReceiverRepository>(relaxed = true)
        every { repository.observeReceivers() } returns flowOf(emptyList())
        val viewModel = buildViewModel(repository)

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
        val viewModel = buildViewModel(repository)
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
        val viewModel = buildViewModel(repository)
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
        val viewModel = buildViewModel(repository)
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
        val viewModel = buildViewModel(repository)

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
    fun addReceiverFormHasMinMaxImageFieldsPrefilledWithDefaults() {
        val repository = mockk<ReceiverRepository>(relaxed = true)
        every { repository.observeReceivers() } returns flowOf(emptyList())
        val viewModel = buildViewModel(repository)

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

        composeTestRule.onNodeWithText("Min images").assertIsDisplayed()
        composeTestRule.onNodeWithText("Max images").assertIsDisplayed()
        // DEFAULT_MIN_COUNT/DEFAULT_MAX_COUNT (2/5) pre-filled for a brand-new receiver.
        composeTestRule.onNodeWithText("2").assertIsDisplayed()
        composeTestRule.onNodeWithText("5").assertIsDisplayed()
    }

    @Test
    fun addReceiverSucceedsWithDefaultMinMaxValuesPrefilled() {
        val repository = mockk<ReceiverRepository>(relaxed = true)
        every { repository.observeReceivers() } returns flowOf(emptyList())
        coEvery { repository.addReceiver(any(), any()) } returns AppResult.Success(Unit)
        val viewModel = buildViewModel(repository, activeImageCount = 10)
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

        assertTrue("expected onDone to fire relying on the pre-filled default min/max values", done)
        coVerify { repository.addReceiver(match { it.minCount == 2 && it.maxCount == 5 }, any()) }
    }

    @Test
    fun addReceiverBlocksSaveWhenMaxIsLessThanMin() {
        val repository = mockk<ReceiverRepository>(relaxed = true)
        every { repository.observeReceivers() } returns flowOf(emptyList())
        val viewModel = buildViewModel(repository)

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

        composeTestRule.onNodeWithText("Name").performTextInput("Priya")
        composeTestRule.onNodeWithText("Phone (+91)").performTextInput("9876543210")
        composeTestRule.onNodeWithText("Max images").performTextClearance()
        composeTestRule.onNodeWithText("Max images").performTextInput("1")
        composeTestRule.onNodeWithText("Save").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Max must be at least the minimum.").assertIsDisplayed()
        coVerify(exactly = 0) { repository.addReceiver(any(), any()) }
    }

    @Test
    fun addReceiverBlocksSaveWhenTheCombinedMinimumExceedsActiveImages() {
        val repository = mockk<ReceiverRepository>(relaxed = true)
        every { repository.observeReceivers() } returns flowOf(emptyList())
        // Only 1 active image, but the default minimum (2) already exceeds that.
        val viewModel = buildViewModel(repository, activeImageCount = 1)

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

        composeTestRule.onNodeWithText("Name").performTextInput("Priya")
        composeTestRule.onNodeWithText("Phone (+91)").performTextInput("9876543210")
        composeTestRule.onNodeWithText("Save").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Not enough active images (1) to guarantee every receiver's minimum (2 needed across all receivers). Add more images or lower a minimum.").assertIsDisplayed()
        coVerify(exactly = 0) { repository.addReceiver(any(), any()) }
    }
}
