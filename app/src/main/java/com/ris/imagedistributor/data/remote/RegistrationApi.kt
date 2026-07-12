package com.ris.imagedistributor.data.remote

import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.POST

/** No auth header — the registration endpoint takes none. [SPEC.md#Constraints] */
interface RegistrationApi {
    @POST(".")
    suspend fun register(@Body request: RegistrationRequest)
}

@Serializable
data class RegistrationRequest(
    val nickname: String,
    val city: String,
)
