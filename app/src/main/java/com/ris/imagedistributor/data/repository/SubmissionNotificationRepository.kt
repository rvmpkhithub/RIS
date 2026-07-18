package com.ris.imagedistributor.data.repository

import com.ris.imagedistributor.domain.AppResult

interface SubmissionNotificationRepository {
    suspend fun notify(nickname: String, city: String, imagesSent: Int): AppResult<Unit>
}
