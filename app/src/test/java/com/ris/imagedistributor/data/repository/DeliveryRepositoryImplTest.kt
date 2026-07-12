package com.ris.imagedistributor.data.repository

import com.ris.imagedistributor.data.local.Image
import com.ris.imagedistributor.data.local.ImageFileStore
import com.ris.imagedistributor.data.local.Receiver
import com.ris.imagedistributor.data.remote.SmtpClient
import com.ris.imagedistributor.data.remote.WhatsAppApi
import com.ris.imagedistributor.data.remote.WhatsAppSendRequest
import com.ris.imagedistributor.domain.AppResult
import com.ris.imagedistributor.domain.FailureReason
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File

class DeliveryRepositoryImplTest {

    private lateinit var whatsAppApi: WhatsAppApi
    private lateinit var smtpClient: SmtpClient
    private lateinit var imageFileStore: ImageFileStore
    private lateinit var repository: DeliveryRepositoryImpl

    private val whatsAppReceiver = Receiver(
        id = 1L, name = "Asha", channel = "WHATSAPP", phoneOrEmail = "+911234567890", minCount = 2, maxCount = 5,
    )
    private val emailReceiver = Receiver(
        id = 2L, name = "Bala", channel = "EMAIL", phoneOrEmail = "bala@example.com", minCount = 2, maxCount = 5,
    )
    private val image = Image(id = 10L, filePath = "photo.jpg", active = true, uploadedAt = 0L)

    @Before
    fun setUp() {
        whatsAppApi = mockk()
        smtpClient = mockk()
        imageFileStore = mockk()
        repository = DeliveryRepositoryImpl(whatsAppApi, smtpClient, imageFileStore)
    }

    @Test
    fun `send dispatches WhatsApp receivers via the WhatsApp API`() = runTest {
        coEvery { whatsAppApi.sendTemplateMessage(any()) } returns Unit

        val result = repository.send(whatsAppReceiver, image)

        assertEquals(AppResult.Success(Unit), result)
        coVerify { whatsAppApi.sendTemplateMessage(match<WhatsAppSendRequest> { it.to == "+911234567890" }) }
    }

    @Test
    fun `send dispatches EMAIL receivers via the SMTP client`() = runTest {
        val file = File("photo.jpg")
        every { imageFileStore.absolutePath("photo.jpg") } returns file
        coEvery { smtpClient.sendImage("bala@example.com", file) } returns Unit

        val result = repository.send(emailReceiver, image)

        assertEquals(AppResult.Success(Unit), result)
        coVerify { smtpClient.sendImage("bala@example.com", file) }
    }

    @Test
    fun `send maps a WhatsApp API failure to Failure UNKNOWN regardless of the underlying cause`() = runTest {
        coEvery { whatsAppApi.sendTemplateMessage(any()) } throws RuntimeException("boom")

        val result = repository.send(whatsAppReceiver, image)

        assertEquals(AppResult.Failure(FailureReason.UNKNOWN), result)
    }

    @Test
    fun `send maps an SMTP failure to Failure UNKNOWN regardless of the underlying cause`() = runTest {
        every { imageFileStore.absolutePath(any()) } returns File("photo.jpg")
        coEvery { smtpClient.sendImage(any(), any()) } throws RuntimeException("smtp boom")

        val result = repository.send(emailReceiver, image)

        assertEquals(AppResult.Failure(FailureReason.UNKNOWN), result)
    }

    @Test
    fun `send does not swallow CancellationException`() = runTest {
        coEvery { whatsAppApi.sendTemplateMessage(any()) } throws CancellationException("cancelled")

        try {
            repository.send(whatsAppReceiver, image)
            fail("expected CancellationException to propagate")
        } catch (e: CancellationException) {
            // expected
        }
    }
}
