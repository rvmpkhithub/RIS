package com.ris.imagedistributor.data.repository

/**
 * INSTRUMENTED — moved here from a JVM unit test when the repository started using
 * AppDatabase.withTransaction across two DAOs plus a Room @Relation query; that's not
 * meaningfully mockable and this project has no Robolectric, so a real (in-memory) Room database
 * is used instead. See SetupScreenTest.kt header for verification-status context.
 */
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ris.imagedistributor.data.local.AppDatabase
import com.ris.imagedistributor.data.local.Receiver
import com.ris.imagedistributor.domain.AppResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReceiverRepositoryImplTest {

    private lateinit var database: AppDatabase
    private lateinit var repository: ReceiverRepositoryImpl

    private val receiver = Receiver(
        name = "Asha",
        channel = "WHATSAPP",
        phoneOrEmail = "+911234567890",
        minCount = 2,
        maxCount = 5,
    )
    private val scheduleTimes = listOf(9 * 60, 12 * 60, 15 * 60, 18 * 60)

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = ReceiverRepositoryImpl(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun addReceiver_persistsTheReceiverAndAllItsScheduleTimes() = runTest {
        val result = repository.addReceiver(receiver, scheduleTimes)

        assertTrue(result is AppResult.Success)
        val stored = repository.observeReceivers().first()
        assertEquals(1, stored.size)
        assertEquals("Asha", stored[0].receiver.name)
        assertEquals(scheduleTimes, stored[0].scheduleTimes)
    }

    @Test
    fun updateReceiver_replacesTheFullScheduleSet() = runTest {
        repository.addReceiver(receiver, scheduleTimes)
        val stored = repository.observeReceivers().first().first().receiver

        val newTimes = listOf(8 * 60, 10 * 60, 14 * 60, 20 * 60, 22 * 60)
        val result = repository.updateReceiver(stored.copy(name = "Asha K"), newTimes)

        assertTrue(result is AppResult.Success)
        val updated = repository.observeReceivers().first().first()
        assertEquals("Asha K", updated.receiver.name)
        assertEquals(newTimes, updated.scheduleTimes)
    }

    @Test
    fun deleteReceiver_removesTheReceiverAndItsScheduleTimes() = runTest {
        repository.addReceiver(receiver, scheduleTimes)
        val stored = repository.observeReceivers().first().first().receiver

        val result = repository.deleteReceiver(stored.id)

        assertTrue(result is AppResult.Success)
        assertEquals(emptyList<Any>(), repository.observeReceivers().first())
    }

    @Test
    fun observeReceivers_startsEmptyWhenNoReceiversExist() = runTest {
        assertEquals(emptyList<Any>(), repository.observeReceivers().first())
    }

    @Test
    fun getAllWithSchedules_returnsAOneShotSnapshotMatchingObserveReceivers() = runTest {
        repository.addReceiver(receiver, scheduleTimes)

        val result = repository.getAllWithSchedules()

        assertTrue(result is AppResult.Success)
        val snapshot = (result as AppResult.Success).value
        assertEquals(1, snapshot.size)
        assertEquals("Asha", snapshot[0].receiver.name)
        assertEquals(scheduleTimes, snapshot[0].scheduleTimes)
    }

    @Test
    fun getAllWithSchedules_returnsEmptyWhenNoReceiversExist() = runTest {
        val result = repository.getAllWithSchedules()

        assertEquals(AppResult.Success(emptyList<Any>()), result)
    }
}
