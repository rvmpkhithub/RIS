package com.ris.imagedistributor.data.repository

import com.ris.imagedistributor.data.local.Receiver
import com.ris.imagedistributor.data.local.ReceiverWithSchedules
import com.ris.imagedistributor.domain.AppResult
import kotlinx.coroutines.flow.Flow

/** Simple CRUD — no domain service layer, same shape as ImageRepository. [AD-5, AD-10] */
interface ReceiverRepository {
    fun observeReceivers(): Flow<List<ReceiverWithSchedules>>
    suspend fun addReceiver(receiver: Receiver, scheduleTimes: List<Int>): AppResult<Unit>
    suspend fun updateReceiver(receiver: Receiver, scheduleTimes: List<Int>): AppResult<Unit>
    suspend fun deleteReceiver(id: Long): AppResult<Unit>

    /** One-shot snapshot for SendWorker (Story 2.2) — distinct from observeReceivers()'s live Flow. */
    suspend fun getAllWithSchedules(): AppResult<List<ReceiverWithSchedules>>
}
