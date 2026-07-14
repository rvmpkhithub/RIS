package com.ris.imagedistributor.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MasterScheduleDao {
    @Query("SELECT * FROM master_schedule ORDER BY time ASC")
    fun observeAll(): Flow<List<MasterSchedule>>

    /** One-shot snapshot for SendDispatcher's dispatch tick — distinct from observeAll()'s live Flow. */
    @Query("SELECT * FROM master_schedule ORDER BY time ASC")
    suspend fun getAllOnce(): List<MasterSchedule>

    @Insert
    suspend fun insert(schedule: MasterSchedule): Long

    @Query("DELETE FROM master_schedule")
    suspend fun deleteAll()
}
