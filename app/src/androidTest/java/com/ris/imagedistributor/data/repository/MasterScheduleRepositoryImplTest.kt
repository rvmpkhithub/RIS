package com.ris.imagedistributor.data.repository

/**
 * INSTRUMENTED — same reasoning as ReceiverRepositoryImplTest.kt's header: this repository uses
 * AppDatabase.withTransaction (an inline extension function, not meaningfully mockable) for its
 * delete-all-then-reinsert replace pattern, and this project has no Robolectric — so a real
 * (in-memory) Room database is used instead of a mocked DAO.
 */
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ris.imagedistributor.data.local.AppDatabase
import com.ris.imagedistributor.domain.AppResult
import com.ris.imagedistributor.domain.FailureReason
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MasterScheduleRepositoryImplTest {

    private lateinit var database: AppDatabase
    private lateinit var repository: MasterScheduleRepositoryImpl

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = MasterScheduleRepositoryImpl(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun getScheduleTimes_returnsDefaultTimesWhenTableIsEmpty() = runTest {
        // A fresh in-memory database never runs MIGRATION_7_8 — Room creates the schema directly
        // from the @Entity annotations, so the table starts genuinely empty. This is exactly the
        // fresh-install case the repository-level default is meant to cover.
        val result = repository.getScheduleTimes()

        assertEquals(AppResult.Success(MasterScheduleRepositoryImpl.DEFAULT_MASTER_SCHEDULE_TIMES), result)
    }

    @Test
    fun getScheduleTimes_returnsStoredTimesInSortedOrderWhenPresent() = runTest {
        repository.setScheduleTimes(listOf(18 * 60, 6 * 60, 12 * 60, 9 * 60))

        val result = repository.getScheduleTimes()

        assertEquals(AppResult.Success(listOf(6 * 60, 9 * 60, 12 * 60, 18 * 60)), result)
    }

    @Test
    fun observeScheduleTimes_fallsBackToDefaultWhenEmpty() = runTest {
        assertEquals(MasterScheduleRepositoryImpl.DEFAULT_MASTER_SCHEDULE_TIMES, repository.observeScheduleTimes().first())
    }

    @Test
    fun observeScheduleTimes_reflectsStoredTimes() = runTest {
        val times = listOf(7 * 60, 11 * 60, 14 * 60, 19 * 60)
        repository.setScheduleTimes(times)

        assertEquals(times, repository.observeScheduleTimes().first())
    }

    @Test
    fun setScheduleTimes_rejectsAnEmptyList() = runTest {
        // Unlike a receiver's own schedule, zero is never valid for the master schedule itself.
        val result = repository.setScheduleTimes(emptyList())

        assertEquals(AppResult.Failure(FailureReason.INVALID_INPUT), result)
    }

    @Test
    fun setScheduleTimes_rejectsAPartiallyFilledList() = runTest {
        val result = repository.setScheduleTimes(listOf(9 * 60, 12 * 60))

        assertEquals(AppResult.Failure(FailureReason.INVALID_INPUT), result)
    }

    @Test
    fun setScheduleTimes_rejectionDoesNotTouchExistingRows() = runTest {
        val original = listOf(8 * 60, 10 * 60, 14 * 60, 20 * 60)
        repository.setScheduleTimes(original)

        val rejected = repository.setScheduleTimes(listOf(9 * 60))

        assertTrue(rejected is AppResult.Failure)
        assertEquals(AppResult.Success(original), repository.getScheduleTimes())
    }

    @Test
    fun setScheduleTimes_replacesTheFullListTransactionally() = runTest {
        repository.setScheduleTimes(listOf(8 * 60, 10 * 60, 14 * 60, 20 * 60))

        val newTimes = listOf(6 * 60, 9 * 60, 13 * 60, 17 * 60, 21 * 60)
        val result = repository.setScheduleTimes(newTimes)

        assertTrue(result is AppResult.Success)
        assertEquals(AppResult.Success(newTimes), repository.getScheduleTimes())
    }
}
