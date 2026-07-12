package com.ris.imagedistributor.data.remote

import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * No auth header. [SPEC.md#Constraints]
 *
 * ASSUMPTION (flagged, not confirmed upstream): response is a 200 OK JSON body carrying a
 * boolean `compliant` field. Any non-2xx status or thrown exception is treated as "unreachable"
 * by the caller (ComplianceRepository), never as "non-compliant" — only compliant == false in a
 * successful response counts as an explicit non-compliant result. [mechanics.md#Compliance flow]
 */
interface ComplianceApi {
    @POST(".")
    suspend fun checkCompliance(@Body request: ComplianceCheckRequest): ComplianceCheckResponse
}

@Serializable
data class ComplianceCheckRequest(
    val nickname: String,
    val city: String,
)

@Serializable
data class ComplianceCheckResponse(
    val compliant: Boolean,
)
