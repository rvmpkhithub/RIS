package com.ris.imagedistributor.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TransmissionDao {
    @Query(
        "SELECT imageId FROM transmissions WHERE receiverId = :receiverId AND status = :status " +
            "AND sentAt >= :sinceEpochMillis"
    )
    suspend fun getSentImageIdsSince(receiverId: Long, status: String, sinceEpochMillis: Long): List<Long>

    @Insert
    suspend fun insert(transmission: Transmission): Long

    @Update
    suspend fun update(transmission: Transmission)

    @Query("SELECT * FROM transmissions WHERE status = :status AND attemptCount < :maxAttempts")
    suspend fun getRetryCandidates(status: String, maxAttempts: Int): List<Transmission>

    @Query(
        "SELECT COUNT(*) FROM transmissions WHERE receiverId = :receiverId AND scheduleTime = :scheduleTime " +
            "AND createdAt >= :startOfDayEpochMillis"
    )
    suspend fun countDispatchedSince(receiverId: Long, scheduleTime: Int, startOfDayEpochMillis: Long): Int

    /**
     * One-shot-per-emission dashboard history, most recent first. Story 3.1. `id DESC` is a
     * tie-breaker for rows sharing the same `sentAt` millisecond — keeps ordering stable across
     * emissions rather than leaving it to SQLite's unspecified tie-order.
     */
    @Transaction
    @Query(
        "SELECT * FROM transmissions WHERE receiverId = :receiverId AND status = :status " +
            "AND sentAt >= :sinceEpochMillis ORDER BY sentAt DESC, id DESC"
    )
    fun observeSentHistory(receiverId: Long, status: String, sinceEpochMillis: Long): Flow<List<TransmissionWithImage>>

    /**
     * CAP-8 purge (Story 3.2). Filters on `createdAt`, not `sentAt` — `sentAt` is null for any
     * row that never reached SENT, so a `sentAt`-based filter would let old failed/abandoned rows
     * accumulate forever, exactly the unbounded-growth problem this exists to prevent.
     */
    @Query("DELETE FROM transmissions WHERE createdAt < :cutoffEpochMillis")
    suspend fun deleteOlderThan(cutoffEpochMillis: Long): Int
}
