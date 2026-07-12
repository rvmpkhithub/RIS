package com.ris.imagedistributor.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ComplianceStateDao {
    @Query("SELECT * FROM compliance_state WHERE id = ${ComplianceState.SINGLETON_ID} LIMIT 1")
    fun observe(): Flow<ComplianceState?>

    @Query("SELECT * FROM compliance_state WHERE id = ${ComplianceState.SINGLETON_ID} LIMIT 1")
    suspend fun getOnce(): ComplianceState?

    @Upsert
    suspend fun upsert(state: ComplianceState)
}
