package com.ris.imagedistributor.ui

/** INSTRUMENTED — see SetupScreenTest.kt header for verification-status context. */
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsDisplayed
import com.ris.imagedistributor.data.local.DeliveryRecord
import com.ris.imagedistributor.data.local.Image
import com.ris.imagedistributor.data.local.Receiver
import com.ris.imagedistributor.data.local.ReceiverWithSchedules
import com.ris.imagedistributor.data.repository.ReceiverRepository
import com.ris.imagedistributor.data.repository.TransmissionRepository
import com.ris.imagedistributor.di.AppContainer
import com.ris.imagedistributor.domain.RetentionPolicy
import com.ris.imagedistributor.ui.dashboard.DashboardScreen
import com.ris.imagedistributor.ui.theme.ImageDropTheme
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.time.temporal.ChronoUnit

class DashboardScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val receiverA = Receiver(id = 1L, name = "Asha", channel = "WHATSAPP", phoneOrEmail = "+911234567890", minCount = 2, maxCount = 5)
    private val receiverB = Receiver(id = 2L, name = "Bala", channel = "EMAIL", phoneOrEmail = "bala@example.com", minCount = 2, maxCount = 5)
    private val image = Image(id = 9L, filePath = "a.jpg", active = true, uploadedAt = 0L)

    private fun containerWith(receivers: List<ReceiverWithSchedules>, historyByReceiver: Map<Long, List<DeliveryRecord>>): AppContainer {
        val receiverRepository = mockk<ReceiverRepository>(relaxed = true)
        every { receiverRepository.observeReceivers() } returns flowOf(receivers)
        val transmissionRepository = mockk<TransmissionRepository>(relaxed = true)
        historyByReceiver.forEach { (id, records) ->
            every { transmissionRepository.observeSentHistory(id, any()) } returns flowOf(records)
        }
        val retentionPolicy = mockk<RetentionPolicy>(relaxed = true)
        every { retentionPolicy.observeCutoff() } returns flowOf(Instant.now().minus(30, ChronoUnit.DAYS))
        val container = mockk<AppContainer>(relaxed = true)
        every { container.receiverRepository } returns receiverRepository
        every { container.transmissionRepository } returns transmissionRepository
        every { container.retentionPolicy } returns retentionPolicy
        return container
    }

    @Test
    fun showsNothingSentYetWhenTheSelectedReceiverHasNoHistory() {
        val container = containerWith(
            receivers = listOf(ReceiverWithSchedules(receiverA, emptyList())),
            historyByReceiver = mapOf(1L to emptyList()),
        )

        composeTestRule.setContent {
            ImageDropTheme { DashboardScreen(container) }
        }

        composeTestRule.onNodeWithText("Nothing sent to this receiver yet.").assertIsDisplayed()
    }

    @Test
    fun showsAPopulatedHistoryRowWhenTransmissionsExist() {
        val record = DeliveryRecord(transmissionId = 1L, image = image, sentAt = Instant.ofEpochMilli(1_700_000_000_000L))
        val container = containerWith(
            receivers = listOf(ReceiverWithSchedules(receiverA, emptyList())),
            historyByReceiver = mapOf(1L to listOf(record)),
        )

        composeTestRule.setContent {
            ImageDropTheme { DashboardScreen(container) }
        }

        composeTestRule.onNodeWithText("Sent").assertIsDisplayed()
    }

    @Test
    fun switchingTheReceiverPickerUpdatesTheVisibleHistory() {
        val recordA = DeliveryRecord(transmissionId = 1L, image = image, sentAt = Instant.ofEpochMilli(1_700_000_000_000L))
        val container = containerWith(
            receivers = listOf(ReceiverWithSchedules(receiverA, emptyList()), ReceiverWithSchedules(receiverB, emptyList())),
            historyByReceiver = mapOf(1L to listOf(recordA), 2L to emptyList()),
        )

        composeTestRule.setContent {
            ImageDropTheme { DashboardScreen(container) }
        }

        composeTestRule.onNodeWithText("Sent").assertIsDisplayed()

        composeTestRule.onNodeWithText("Asha").performClick()
        composeTestRule.onNodeWithText("Bala").performClick()

        composeTestRule.onNodeWithText("Nothing sent to this receiver yet.").assertIsDisplayed()
    }
}
