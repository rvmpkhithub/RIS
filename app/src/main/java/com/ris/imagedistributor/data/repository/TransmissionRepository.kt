package com.ris.imagedistributor.data.repository

import com.ris.imagedistributor.data.local.DeliveryRecord
import com.ris.imagedistributor.data.local.Transmission
import com.ris.imagedistributor.domain.AppResult
import kotlinx.coroutines.flow.Flow
import java.time.Instant

/**
 * A thin AppResult-wrapping CRUD/query layer, same shape as every other repository — no
 * retry/terminal-state decision logic here. That is a CAP-4 business rule and belongs in
 * `worker.SendDispatcher`, which calls `update(...)` with an already-decided next state. [AD-5, AD-10]
 */
interface TransmissionRepository {
    suspend fun getRecentlySentImageIds(receiverId: Long, since: Instant): AppResult<List<Long>>
    suspend fun enqueue(receiverId: Long, imageId: Long, scheduleTime: Int, queuedAt: Instant): AppResult<Transmission>
    suspend fun update(transmission: Transmission): AppResult<Unit>
    suspend fun getRetryCandidates(): AppResult<List<Transmission>>
    suspend fun hasDispatchedToday(receiverId: Long, scheduleTime: Int, todayStart: Instant): AppResult<Boolean>

    /** Dashboard history for one receiver, most recent first — Story 3.1. */
    fun observeSentHistory(receiverId: Long, since: Instant): Flow<List<DeliveryRecord>>

    /** CAP-8 purge (Story 3.2) — deletes rows older than [cutoff] by createdAt. Returns the deleted row count. */
    suspend fun purgeOlderThan(cutoff: Instant): AppResult<Int>

    companion object {
        /** "up to 3 times" per AC2/mechanics.md — shared by the retry-candidate query and
         * `SendDispatcher`'s terminal-state decision; defined once here (data layer) since
         * `worker.SendDispatcher` depends on `data.repository`, not the other way around. [AD-1] */
        const val MAX_SEND_ATTEMPTS: Int = 3
    }
}
