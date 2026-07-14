package com.ris.imagedistributor.data.repository

import com.ris.imagedistributor.domain.AppResult
import kotlinx.coroutines.flow.Flow

/**
 * Thin AppResult-wrapping CRUD layer for the app-wide `MasterSchedule` table, same shape as
 * every other repository. Unlike a receiver's own schedule, this list is never valid empty —
 * see [MasterScheduleRepositoryImpl.setScheduleTimes]. [ARCHITECTURE-SPINE.md#AD-16]
 */
interface MasterScheduleRepository {
    fun observeScheduleTimes(): Flow<List<Int>>
    suspend fun getScheduleTimes(): AppResult<List<Int>>
    suspend fun setScheduleTimes(times: List<Int>): AppResult<Unit>
}
