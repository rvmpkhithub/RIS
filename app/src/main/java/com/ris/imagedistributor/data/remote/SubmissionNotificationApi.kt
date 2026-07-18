package com.ris.imagedistributor.data.remote

import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.POST

/** No auth header — same host/pattern as RegistrationApi/ComplianceApi. */
interface SubmissionNotificationApi {
    @POST(".")
    suspend fun notify(@Body request: SubmissionNotificationRequest)
}

@Serializable
data class SubmissionNotificationRequest(
    val nickname: String,
    val city: String,
    val imagesSent: Int,
)
