package com.ris.imagedistributor.data.repository

import com.ris.imagedistributor.data.remote.SubmissionNotificationApi
import com.ris.imagedistributor.data.remote.SubmissionNotificationRequest
import com.ris.imagedistributor.domain.AppResult
import com.ris.imagedistributor.domain.FailureReason
import kotlinx.coroutines.CancellationException
import retrofit2.HttpException
import java.io.IOException

/**
 * Best-effort reporting call, not a business rule — SendDispatcher's call site never blocks or
 * retries the underlying image delivery (which already happened) on this failing.
 */
class SubmissionNotificationRepositoryImpl(
    private val api: SubmissionNotificationApi,
) : SubmissionNotificationRepository {

    override suspend fun notify(nickname: String, city: String, imagesSent: Int): AppResult<Unit> =
        try {
            api.notify(SubmissionNotificationRequest(nickname, city, imagesSent))
            AppResult.Success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            AppResult.Failure(FailureReason.NETWORK_UNREACHABLE)
        } catch (e: HttpException) {
            AppResult.Failure(FailureReason.SERVER_ERROR)
        } catch (e: Exception) {
            AppResult.Failure(FailureReason.UNKNOWN)
        }
}
