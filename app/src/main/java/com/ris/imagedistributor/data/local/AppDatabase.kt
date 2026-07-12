package com.ris.imagedistributor.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Entities registered here grow one story at a time — extend this file, don't create a second
 * database class.
 *
 * Once this ships to a real device with real data, every schema change ships an explicit
 * Migration; fallbackToDestructiveMigration() must never be used. [AD-15]
 */
@Database(
    entities = [
        ComplianceState::class,
        Image::class,
        Receiver::class,
        ReceiverSchedule::class,
        Transmission::class,
        RetentionSetting::class,
    ],
    version = 7,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun complianceStateDao(): ComplianceStateDao
    abstract fun imageDao(): ImageDao
    abstract fun receiverDao(): ReceiverDao
    abstract fun receiverScheduleDao(): ReceiverScheduleDao
    abstract fun transmissionDao(): TransmissionDao
    abstract fun retentionSettingDao(): RetentionSettingDao

    companion object {
        const val DATABASE_NAME: String = "image-distributor.db"

        /** Adds the `images` table (Story 1.2) — must not touch the existing compliance_state row. */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `images` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`filePath` TEXT NOT NULL, `active` INTEGER NOT NULL, `uploadedAt` INTEGER NOT NULL)"
                )
            }
        }

        /** Adds the `receivers` table (Story 1.3) — must not touch compliance_state or images. */
        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `receivers` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, `channel` TEXT NOT NULL, `phoneOrEmail` TEXT NOT NULL, " +
                        "`minCount` INTEGER NOT NULL, `maxCount` INTEGER NOT NULL, `scheduleTime` INTEGER NOT NULL)"
                )
            }
        }

        /**
         * Replaces the single `Receiver.scheduleTime` column with a one-to-many
         * `receiver_schedules` table (Story 1.3 rework — Sprint Change Proposal 2026-07-10:
         * receivers need 4+ daily schedule times, not one). SQLite has no reliable cross-version
         * DROP COLUMN, so `receivers` is recreated via the copy-and-rename pattern. Each
         * receiver's existing single scheduleTime is preserved as its first schedule row rather
         * than discarded — no destructive fallback, per AD-15.
         */
        val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `receiver_schedules` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`receiverId` INTEGER NOT NULL, `time` INTEGER NOT NULL, " +
                        "FOREIGN KEY(`receiverId`) REFERENCES `receivers`(`id`) ON DELETE CASCADE)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_receiver_schedules_receiverId` ON `receiver_schedules` (`receiverId`)"
                )
                db.execSQL(
                    "INSERT INTO `receiver_schedules` (`receiverId`, `time`) SELECT `id`, `scheduleTime` FROM `receivers`"
                )

                db.execSQL(
                    "CREATE TABLE `receivers_new` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, `channel` TEXT NOT NULL, `phoneOrEmail` TEXT NOT NULL, " +
                        "`minCount` INTEGER NOT NULL, `maxCount` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "INSERT INTO `receivers_new` (`id`, `name`, `channel`, `phoneOrEmail`, `minCount`, `maxCount`) " +
                        "SELECT `id`, `name`, `channel`, `phoneOrEmail`, `minCount`, `maxCount` FROM `receivers`"
                )
                db.execSQL("DROP TABLE `receivers`")
                db.execSQL("ALTER TABLE `receivers_new` RENAME TO `receivers`")
            }
        }

        /** Adds the `transmissions` table (Story 2.1) — brand-new entity, nothing to preserve. */
        val MIGRATION_4_5: Migration = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `transmissions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`receiverId` INTEGER NOT NULL, `imageId` INTEGER NOT NULL, `status` TEXT NOT NULL, " +
                        "`attemptCount` INTEGER NOT NULL, `sentAt` INTEGER)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_transmissions_receiverId_status_sentAt` " +
                        "ON `transmissions` (`receiverId`, `status`, `sentAt`)"
                )
            }
        }

        /**
         * Adds `createdAt`/`scheduleTime` to `transmissions` (Story 2.2) — needed for SendWorker to
         * detect an already-dispatched-today schedule slot. `DEFAULT 0` is required by SQLite for a
         * NOT NULL ADD COLUMN; harmless here since only test/emulator data exists on this table so
         * far, nothing real to preserve.
         */
        val MIGRATION_5_6: Migration = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `transmissions` ADD COLUMN `createdAt` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `transmissions` ADD COLUMN `scheduleTime` INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_transmissions_receiverId_scheduleTime_createdAt` " +
                        "ON `transmissions` (`receiverId`, `scheduleTime`, `createdAt`)"
                )
            }
        }

        /**
         * Adds the `retention_settings` singleton table (Story 3.2) and seeds its one row with
         * mechanics.md's stated default (30 days) — unlike `compliance_state`, there is no reason
         * to wait for a lazy upsert since the default is known at migration time.
         */
        val MIGRATION_6_7: Migration = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `retention_settings` (`id` INTEGER NOT NULL PRIMARY KEY, " +
                        "`retentionDays` INTEGER NOT NULL)"
                )
                db.execSQL("INSERT INTO `retention_settings` (`id`, `retentionDays`) VALUES (1, 30)")
            }
        }
    }
}
