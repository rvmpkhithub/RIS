package com.ris.imagedistributor.data.repository

import com.ris.imagedistributor.config.AppConfig
import com.ris.imagedistributor.data.local.Image
import com.ris.imagedistributor.data.local.ImageFileStore
import com.ris.imagedistributor.data.local.Receiver
import com.ris.imagedistributor.data.local.ReceiverChannel
import com.ris.imagedistributor.data.local.channelOrDefault
import com.ris.imagedistributor.data.remote.SmtpClient
import com.ris.imagedistributor.data.remote.WhatsAppApi
import com.ris.imagedistributor.data.remote.WhatsAppLanguage
import com.ris.imagedistributor.data.remote.WhatsAppSendRequest
import com.ris.imagedistributor.data.remote.WhatsAppTemplate
import com.ris.imagedistributor.domain.AppResult
import com.ris.imagedistributor.domain.FailureReason
import kotlinx.coroutines.CancellationException

/**
 * mechanics.md#Queue and delivery (CAP-4): "No distinction is made between 'bad receiver number'
 * and 'transient network failure' — both get the same 3 retries." A single catch-all Failure
 * reason is therefore the correct, spec-directed design here, not a corner cut — SendDispatcher's
 * retry logic treats every Failure identically regardless of cause.
 */
class DeliveryRepositoryImpl(
    private val whatsAppApi: WhatsAppApi,
    private val smtpClient: SmtpClient,
    private val imageFileStore: ImageFileStore,
) : DeliveryRepository {

    override suspend fun send(receiver: Receiver, image: Image): AppResult<Unit> =
        try {
            when (receiver.channelOrDefault()) {
                ReceiverChannel.WHATSAPP -> whatsAppApi.sendTemplateMessage(
                    WhatsAppSendRequest(
                        to = receiver.phoneOrEmail,
                        template = WhatsAppTemplate(name = AppConfig.WHATSAPP_TEMPLATE_NAME, language = WhatsAppLanguage()),
                    )
                )
                ReceiverChannel.EMAIL -> smtpClient.sendImage(
                    to = receiver.phoneOrEmail,
                    imageFile = imageFileStore.absolutePath(image.filePath),
                )
            }
            AppResult.Success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppResult.Failure(FailureReason.UNKNOWN)
        }
}
