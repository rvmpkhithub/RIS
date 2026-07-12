package com.ris.imagedistributor.data.repository

import com.ris.imagedistributor.domain.AppResult
import kotlinx.coroutines.flow.Flow

/** Thin AppResult-wrapping CRUD layer for the singleton `RetentionSetting` row — same shape as every other repository. */
interface RetentionRepository {
    fun observeRetentionDays(): Flow<Int>
    suspend fun getRetentionDays(): AppResult<Int>
    suspend fun setRetentionDays(days: Int): AppResult<Unit>
}
