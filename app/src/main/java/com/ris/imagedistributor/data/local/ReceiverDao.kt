package com.ris.imagedistributor.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ReceiverDao {
    @Transaction
    @Query("SELECT * FROM receivers ORDER BY name ASC")
    fun observeAllWithSchedules(): Flow<List<ReceiverWithScheduleEntities>>

    /** One-shot snapshot for SendWorker — distinct from observeAllWithSchedules()'s live Flow. */
    @Transaction
    @Query("SELECT * FROM receivers ORDER BY name ASC")
    suspend fun getAllWithSchedules(): List<ReceiverWithScheduleEntities>

    @Insert
    suspend fun insert(receiver: Receiver): Long

    @Update
    suspend fun update(receiver: Receiver)

    @Query("DELETE FROM receivers WHERE id = :id")
    suspend fun deleteById(id: Long)
}
