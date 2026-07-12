package com.ris.imagedistributor.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface RetentionSettingDao {
    @Query("SELECT * FROM retention_settings WHERE id = ${RetentionSetting.SINGLETON_ID} LIMIT 1")
    fun observe(): Flow<RetentionSetting?>

    @Query("SELECT * FROM retention_settings WHERE id = ${RetentionSetting.SINGLETON_ID} LIMIT 1")
    suspend fun getOnce(): RetentionSetting?

    /**
     * [Live-verification fix] MIGRATION_6_7 only seeds the id=1 row for an app being upgraded
     * from an existing v6 database — a genuinely fresh install never runs any Migration at all
     * (Room creates the schema directly from the @Entity annotations), so the row does not
     * always exist. @Update alone would then silently no-op on save. @Upsert (same as
     * ComplianceStateDao's own established convention) makes save work regardless of whether the
     * migration path or the fresh-install path created the database.
     */
    @Upsert
    suspend fun upsert(setting: RetentionSetting)
}
