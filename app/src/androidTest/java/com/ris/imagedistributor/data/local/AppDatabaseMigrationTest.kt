package com.ris.imagedistributor.data.local

/**
 * INSTRUMENTED — exercises real SQLite migrations via MigrationTestHelper. MIGRATION_3_4 is
 * riskier than the prior CREATE-TABLE-only migrations (it recreates `receivers` to drop
 * `scheduleTime`), so it gets dedicated coverage rather than relying on live-device verification
 * alone — [Review][Patch], Story 1.2's own MIGRATION_1_2 now does too, closing a gap disclosed in
 * that story's own Dev Notes ("skipped for time, confirmed correct only via one live install").
 */
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationTest {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    // [Review][Patch] Story 1.2's migration had no dedicated test — only the full-chain test
    // exercised it implicitly. Mirrors migrate4To5_createsTransmissionsTable's shape exactly.
    @Test
    fun migrate1To2_createsImagesTable() {
        helper.createDatabase(TEST_DB, 1).close()

        val db = helper.runMigrationsAndValidate(TEST_DB, 2, true, AppDatabase.MIGRATION_1_2)

        // Brand-new table, nothing to preserve — just prove it exists and is writable/queryable.
        db.execSQL(
            "INSERT INTO images (filePath, active, uploadedAt) VALUES ('a.jpg', 1, 1000)"
        )
        val cursor = db.query("SELECT filePath, active, uploadedAt FROM images WHERE filePath = 'a.jpg'")
        assert(cursor.moveToFirst()) { "expected the inserted image row to be queryable" }
        assert(cursor.getInt(cursor.getColumnIndexOrThrow("active")) == 1)
        cursor.close()
    }

    @Test
    fun migrate3To4_preservesExistingReceiverAsItsFirstScheduleTime() {
        helper.createDatabase(TEST_DB, 3).apply {
            execSQL(
                "INSERT INTO receivers (id, name, channel, phoneOrEmail, minCount, maxCount, scheduleTime) " +
                    "VALUES (1, 'Asha', 'WHATSAPP', '+911234567890', 2, 5, 540)"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 4, true, AppDatabase.MIGRATION_3_4)

        val receiverCursor = db.query("SELECT id, name, channel, phoneOrEmail, minCount, maxCount FROM receivers")
        assert(receiverCursor.moveToFirst()) { "expected the migrated receiver row to survive" }
        assert(receiverCursor.getString(receiverCursor.getColumnIndexOrThrow("name")) == "Asha")
        receiverCursor.close()

        val scheduleCursor = db.query("SELECT receiverId, time FROM receiver_schedules WHERE receiverId = 1")
        assert(scheduleCursor.moveToFirst()) { "expected the old scheduleTime to be preserved as a schedule row" }
        assert(scheduleCursor.getInt(scheduleCursor.getColumnIndexOrThrow("time")) == 540)
        scheduleCursor.close()
    }

    @Test
    fun migrate1Through4_succeedsAgainstAFreshV1Database() {
        helper.createDatabase(TEST_DB, 1).close()

        helper.runMigrationsAndValidate(
            TEST_DB,
            4,
            true,
            AppDatabase.MIGRATION_1_2,
            AppDatabase.MIGRATION_2_3,
            AppDatabase.MIGRATION_3_4,
        )
    }

    @Test
    fun migrate4To5_createsTransmissionsTable() {
        helper.createDatabase(TEST_DB, 4).close()

        val db = helper.runMigrationsAndValidate(TEST_DB, 5, true, AppDatabase.MIGRATION_4_5)

        // Brand-new table, nothing to preserve — just prove it exists and is writable/queryable.
        db.execSQL(
            "INSERT INTO transmissions (receiverId, imageId, status, attemptCount, sentAt) " +
                "VALUES (1, 1, 'SENT', 1, 1000)"
        )
        val cursor = db.query("SELECT receiverId, imageId, status FROM transmissions WHERE receiverId = 1")
        assert(cursor.moveToFirst()) { "expected the inserted transmission row to be queryable" }
        assert(cursor.getString(cursor.getColumnIndexOrThrow("status")) == "SENT")
        cursor.close()
    }

    @Test
    fun migrate5To6_addsCreatedAtAndScheduleTimeColumns() {
        helper.createDatabase(TEST_DB, 5).apply {
            execSQL(
                "INSERT INTO transmissions (receiverId, imageId, status, attemptCount, sentAt) " +
                    "VALUES (1, 1, 'SENT', 1, 1000)"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 6, true, AppDatabase.MIGRATION_5_6)

        val cursor = db.query("SELECT receiverId, createdAt, scheduleTime FROM transmissions WHERE receiverId = 1")
        assert(cursor.moveToFirst()) { "expected the pre-existing row to survive the migration" }
        assert(cursor.getLong(cursor.getColumnIndexOrThrow("createdAt")) == 0L) { "expected the DEFAULT 0 backfill for a pre-existing row" }
        assert(cursor.getInt(cursor.getColumnIndexOrThrow("scheduleTime")) == 0) { "expected the DEFAULT 0 backfill for a pre-existing row" }
        cursor.close()

        // New rows can also supply real values for the new columns.
        db.execSQL(
            "INSERT INTO transmissions (receiverId, imageId, status, attemptCount, sentAt, createdAt, scheduleTime) " +
                "VALUES (2, 2, 'PENDING', 0, NULL, 5000, 540)"
        )
        val newCursor = db.query("SELECT createdAt, scheduleTime FROM transmissions WHERE receiverId = 2")
        assert(newCursor.moveToFirst()) { "expected the newly inserted row to be queryable" }
        assert(newCursor.getLong(newCursor.getColumnIndexOrThrow("createdAt")) == 5000L)
        assert(newCursor.getInt(newCursor.getColumnIndexOrThrow("scheduleTime")) == 540)
        newCursor.close()
    }

    @Test
    fun migrate6To7_seedsTheDefaultRetentionSettingRow() {
        helper.createDatabase(TEST_DB, 6).close()

        val db = helper.runMigrationsAndValidate(TEST_DB, 7, true, AppDatabase.MIGRATION_6_7)

        val cursor = db.query("SELECT id, retentionDays FROM retention_settings")
        assert(cursor.count == 1) { "expected exactly one seeded retention_settings row" }
        assert(cursor.moveToFirst())
        assert(cursor.getLong(cursor.getColumnIndexOrThrow("id")) == 1L)
        assert(cursor.getInt(cursor.getColumnIndexOrThrow("retentionDays")) == 30) { "expected mechanics.md's stated default of 30" }
        cursor.close()
    }

    @Test
    fun migrate1Through7_succeedsAgainstAFreshV1Database() {
        helper.createDatabase(TEST_DB, 1).close()

        helper.runMigrationsAndValidate(
            TEST_DB,
            7,
            true,
            AppDatabase.MIGRATION_1_2,
            AppDatabase.MIGRATION_2_3,
            AppDatabase.MIGRATION_3_4,
            AppDatabase.MIGRATION_4_5,
            AppDatabase.MIGRATION_5_6,
            AppDatabase.MIGRATION_6_7,
        )
    }

    private companion object {
        const val TEST_DB = "migration-test"
    }
}
