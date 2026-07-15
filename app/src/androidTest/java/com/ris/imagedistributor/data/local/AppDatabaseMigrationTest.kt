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
    fun migrate7To8_createsMasterScheduleTableWithFourSeededRows() {
        helper.createDatabase(TEST_DB, 7).close()

        val db = helper.runMigrationsAndValidate(TEST_DB, 8, true, AppDatabase.MIGRATION_7_8)

        val cursor = db.query("SELECT time FROM master_schedule ORDER BY time ASC")
        assert(cursor.count == 4) { "expected exactly 4 seeded master_schedule rows" }
        val expected = listOf(540, 720, 900, 1080)
        var index = 0
        while (cursor.moveToNext()) {
            assert(cursor.getInt(cursor.getColumnIndexOrThrow("time")) == expected[index]) { "expected seeded time ${expected[index]} at row $index" }
            index++
        }
        cursor.close()
    }

    @Test
    fun migrate8To9_addsTitleAndDescriptionColumns() {
        helper.createDatabase(TEST_DB, 8).apply {
            execSQL(
                "INSERT INTO images (id, filePath, active, uploadedAt) VALUES (1, 'a.jpg', 1, 1000)"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 9, true, AppDatabase.MIGRATION_8_9)

        val cursor = db.query("SELECT title, description FROM images WHERE id = 1")
        assert(cursor.moveToFirst()) { "expected the pre-existing row to survive the migration" }
        assert(cursor.isNull(cursor.getColumnIndexOrThrow("title"))) { "expected title to be NULL for a pre-existing row" }
        assert(cursor.isNull(cursor.getColumnIndexOrThrow("description"))) { "expected description to be NULL for a pre-existing row" }
        cursor.close()

        db.execSQL(
            "INSERT INTO images (id, filePath, active, uploadedAt, title, description) " +
                "VALUES (2, 'b.jpg', 1, 2000, 'Sunset', 'A nice sunset')"
        )
        val newCursor = db.query("SELECT title, description FROM images WHERE id = 2")
        assert(newCursor.moveToFirst()) { "expected the newly inserted row to be queryable" }
        assert(newCursor.getString(newCursor.getColumnIndexOrThrow("title")) == "Sunset")
        assert(newCursor.getString(newCursor.getColumnIndexOrThrow("description")) == "A nice sunset")
        newCursor.close()
    }

    @Test
    fun migrate9To10_dropsMinMaxCountColumnsFromReceivers() {
        helper.createDatabase(TEST_DB, 9).apply {
            execSQL(
                "INSERT INTO receivers (id, name, channel, phoneOrEmail, minCount, maxCount) " +
                    "VALUES (1, 'Asha', 'WHATSAPP', '+911234567890', 2, 5)"
            )
            execSQL(
                "INSERT INTO receiver_schedules (receiverId, time) VALUES (1, 540)"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 10, true, AppDatabase.MIGRATION_9_10)

        val columnNames = db.query("SELECT * FROM receivers").columnNames.toList()
        assert(!columnNames.contains("minCount")) { "expected minCount to be dropped, got columns $columnNames" }
        assert(!columnNames.contains("maxCount")) { "expected maxCount to be dropped, got columns $columnNames" }
        assert(columnNames.containsAll(listOf("id", "name", "channel", "phoneOrEmail"))) {
            "expected id/name/channel/phoneOrEmail to survive, got columns $columnNames"
        }

        val receiverCursor = db.query("SELECT id, name, channel, phoneOrEmail FROM receivers WHERE id = 1")
        assert(receiverCursor.moveToFirst()) { "expected the migrated receiver row to survive" }
        assert(receiverCursor.getString(receiverCursor.getColumnIndexOrThrow("name")) == "Asha")
        assert(receiverCursor.getString(receiverCursor.getColumnIndexOrThrow("channel")) == "WHATSAPP")
        assert(receiverCursor.getString(receiverCursor.getColumnIndexOrThrow("phoneOrEmail")) == "+911234567890")
        receiverCursor.close()

        // receiver_schedules' FK-referenced id must still resolve correctly — the copy-and-rename
        // preserved the same id, so this row should still be linked to the same receiver.
        val scheduleCursor = db.query("SELECT receiverId, time FROM receiver_schedules WHERE receiverId = 1")
        assert(scheduleCursor.moveToFirst()) { "expected the pre-existing schedule row to still resolve to receiver id 1" }
        assert(scheduleCursor.getInt(scheduleCursor.getColumnIndexOrThrow("time")) == 540)
        scheduleCursor.close()
    }

    @Test
    fun migrate10To11_restoresMinMaxCountColumnsOnReceivers() {
        helper.createDatabase(TEST_DB, 10).apply {
            execSQL(
                "INSERT INTO receivers (id, name, channel, phoneOrEmail) VALUES (1, 'Asha', 'WHATSAPP', '+911234567890')"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 11, true, AppDatabase.MIGRATION_10_11)

        val columnNames = db.query("SELECT * FROM receivers").columnNames.toList()
        assert(columnNames.containsAll(listOf("minCount", "maxCount"))) {
            "expected minCount/maxCount to be restored, got columns $columnNames"
        }

        val cursor = db.query("SELECT id, name, minCount, maxCount FROM receivers WHERE id = 1")
        assert(cursor.moveToFirst()) { "expected the migrated receiver row to survive" }
        assert(cursor.getString(cursor.getColumnIndexOrThrow("name")) == "Asha")
        assert(cursor.getInt(cursor.getColumnIndexOrThrow("minCount")) == 2) { "expected the backfilled default of 2" }
        assert(cursor.getInt(cursor.getColumnIndexOrThrow("maxCount")) == 5) { "expected the backfilled default of 5" }
        cursor.close()

        // New rows can also supply real values for the restored columns.
        db.execSQL(
            "INSERT INTO receivers (id, name, channel, phoneOrEmail, minCount, maxCount) " +
                "VALUES (2, 'Kiran', 'EMAIL', 'kiran@example.com', 3, 8)"
        )
        val newCursor = db.query("SELECT minCount, maxCount FROM receivers WHERE id = 2")
        assert(newCursor.moveToFirst()) { "expected the newly inserted row to be queryable" }
        assert(newCursor.getInt(newCursor.getColumnIndexOrThrow("minCount")) == 3)
        assert(newCursor.getInt(newCursor.getColumnIndexOrThrow("maxCount")) == 8)
        newCursor.close()
    }

    @Test
    fun migrate1Through11_succeedsAgainstAFreshV1Database() {
        helper.createDatabase(TEST_DB, 1).close()

        helper.runMigrationsAndValidate(
            TEST_DB,
            11,
            true,
            AppDatabase.MIGRATION_1_2,
            AppDatabase.MIGRATION_2_3,
            AppDatabase.MIGRATION_3_4,
            AppDatabase.MIGRATION_4_5,
            AppDatabase.MIGRATION_5_6,
            AppDatabase.MIGRATION_6_7,
            AppDatabase.MIGRATION_7_8,
            AppDatabase.MIGRATION_8_9,
            AppDatabase.MIGRATION_9_10,
            AppDatabase.MIGRATION_10_11,
        )
    }

    private companion object {
        const val TEST_DB = "migration-test"
    }
}
