package com.ris.imagedistributor.data.remote

import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * WhatsApp Business Cloud API — template message send. [ARCHITECTURE-SPINE.md#Stack, mechanics.md#Delivery channels]
 *
 * ASSUMPTION (flagged, not confirmed upstream, same as ComplianceApi's header comment): the
 * WhatsApp Business account is still being provisioned (SPEC.md#Constraints), so the exact
 * approved template name/components and how the image itself is attached (an approved template's
 * media header, referencing an uploaded WhatsApp media id) are unconfirmed. The request shape here
 * follows the Cloud API's documented template-message contract; `AppConfig.WHATSAPP_TEMPLATE_NAME`
 * keeps the template identity swappable once the account is provisioned.
 */
interface WhatsAppApi {
    @POST(".")
    suspend fun sendTemplateMessage(@Body request: WhatsAppSendRequest)
}

@Serializable
data class WhatsAppSendRequest(
    val messaging_product: String = "whatsapp",
    val to: String,
    val type: String = "template",
    val template: WhatsAppTemplate,
)

@Serializable
data class WhatsAppTemplate(
    val name: String,
    val language: WhatsAppLanguage,
)

@Serializable
data class WhatsAppLanguage(
    val code: String = "en_US",
)
