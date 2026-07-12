package com.ris.imagedistributor.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ReceiverScheduleDao {
    @Query("SELECT * FROM receiver_schedules WHERE receiverId = :receiverId ORDER BY time ASC")
    fun observeForReceiver(receiverId: Long): Flow<List<ReceiverSchedule>>

    @Insert
    suspend fun insert(schedule: ReceiverSchedule): Long

    @Query("DELETE FROM receiver_schedules WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM receiver_schedules WHERE receiverId = :receiverId")
    suspend fun deleteAllForReceiver(receiverId: Long)
}
