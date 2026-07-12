package com.ris.imagedistributor.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Singleton row (id always 1). MIGRATION_6_7 seeds it directly for an app upgrading from an
 * existing v6 database, but a fresh install never runs that migration (Room creates the schema
 * straight from this @Entity), so the row does not always exist — [RetentionSettingDao] uses
 * @Upsert, and [com.ris.imagedistributor.data.repository.RetentionRepositoryImpl] falls back to
 * a default when reading a missing row, so both save and read are correct either way.
 */
@Entity(tableName = "retention_settings")
data class RetentionSetting(
    @PrimaryKey val id: Long = SINGLETON_ID,
    val retentionDays: Int,
) {
    companion object {
        const val SINGLETON_ID: Long = 1L
    }
}
