package com.ris.imagedistributor.data.repository

/**
 * INSTRUMENTED — exercises TransmissionRepositoryImpl's `getRecentlySentImageIds` query against a
 * real (in-memory) Room database, seeded via raw SQL, so the actual SQL (including the
 * status-as-bound-parameter fix and the receiverId/sentAt filtering) is verified end-to-end rather
 * than only through a mocked DAO.
 */
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ris.imagedistributor.data.local.AppDatabase
import com.ris.imagedistributor.data.local.DeliveryRecord
import com.ris.imagedistributor.data.local.Image
import com.ris.imagedistributor.domain.AppResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class TransmissionRepositoryImplTest {

    private lateinit var database: AppDatabase
    private lateinit var repository: TransmissionRepositoryImpl

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = TransmissionRepositoryImpl(database.transmissionDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun getRecentlySentImageIds_returnsOnlySentRowsForTheReceiverSinceTheGivenTime() = runTest {
        val now = System.currentTimeMillis()
        val eightDaysAgo = now - 8 * 24 * 60 * 60 * 1000L
        val twoDaysAgo = now - 2 * 24 * 60 * 60 * 1000L
        database.openHelper.writableDatabase.apply {
            // Matches: receiver 1, SENT, within window.
            execSQL(
                "INSERT INTO transmissions (receiverId, imageId, status, attemptCount, sentAt, createdAt, scheduleTime) " +
                    "VALUES (1, 100, 'SENT', 1, $twoDaysAgo, $twoDaysAgo, 540)"
            )
            // Wrong receiver.
            execSQL(
                "INSERT INTO transmissions (receiverId, imageId, status, attemptCount, sentAt, createdAt, scheduleTime) " +
                    "VALUES (2, 200, 'SENT', 1, $twoDaysAgo, $twoDaysAgo, 540)"
            )
            // Right receiver, but not SENT.
            execSQL(
                "INSERT INTO transmissions (receiverId, imageId, status, attemptCount, sentAt, createdAt, scheduleTime) " +
                    "VALUES (1, 300, 'PENDING', 0, NULL, $twoDaysAgo, 540)"
            )
            // Right receiver, SENT, but outside the 7-day window.
            execSQL(
                "INSERT INTO transmissions (receiverId, imageId, status, attemptCount, sentAt, createdAt, scheduleTime) " +
                    "VALUES (1, 400, 'SENT', 1, $eightDaysAgo, $eightDaysAgo, 540)"
            )
        }

        val since = Instant.now().minusSeconds(7 * 24 * 60 * 60L)
        val result = repository.getRecentlySentImageIds(1L, since)

        assertEquals(AppResult.Success(listOf(100L)), result)
    }

    @Test
    fun observeSentHistory_joinsTheRealImageRowViaRoomRelationAndFiltersToTheGivenReceiver() = runTest {
        val now = System.currentTimeMillis()
        val twoDaysAgo = now - 2 * 24 * 60 * 60 * 1000L
        database.imageDao().insert(Image(id = 100, filePath = "a.jpg", active = true, uploadedAt = twoDaysAgo))
        database.imageDao().insert(Image(id = 200, filePath = "b.jpg", active = true, uploadedAt = twoDaysAgo))
        database.openHelper.writableDatabase.apply {
            // Matches: receiver 1, SENT, within window — should join to the real Image row above.
            execSQL(
                "INSERT INTO transmissions (receiverId, imageId, status, attemptCount, sentAt, createdAt, scheduleTime) " +
                    "VALUES (1, 100, 'SENT', 1, $twoDaysAgo, $twoDaysAgo, 540)"
            )
            // Wrong receiver — must not appear in receiver 1's history.
            execSQL(
                "INSERT INTO transmissions (receiverId, imageId, status, attemptCount, sentAt, createdAt, scheduleTime) " +
                    "VALUES (2, 200, 'SENT', 1, $twoDaysAgo, $twoDaysAgo, 540)"
            )
            // Right receiver, but not SENT — must not appear.
            execSQL(
                "INSERT INTO transmissions (receiverId, imageId, status, attemptCount, sentAt, createdAt, scheduleTime) " +
                    "VALUES (1, 100, 'PENDING', 0, NULL, $twoDaysAgo, 540)"
            )
        }

        val since = Instant.ofEpochMilli(now - 30L * 24 * 60 * 60 * 1000L)
        val history = repository.observeSentHistory(1L, since).first()

        assertEquals(listOf(DeliveryRecord(transmissionId = 1L, image = Image(id = 100, filePath = "a.jpg", active = true, uploadedAt = twoDaysAgo), sentAt = Instant.ofEpochMilli(twoDaysAgo))), history)
    }
}
