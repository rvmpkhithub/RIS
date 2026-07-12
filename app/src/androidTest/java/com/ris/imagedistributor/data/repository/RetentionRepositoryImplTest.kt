package com.ris.imagedistributor.data.repository

/**
 * INSTRUMENTED — exercises RetentionRepositoryImpl against a real (in-memory) Room database
 * built directly via Room.inMemoryDatabaseBuilder, NOT seeded through MIGRATION_6_7. This
 * mirrors exactly what happens on a genuinely fresh app install: Room creates the schema
 * straight from the @Entity annotations, so `retention_settings` starts out completely empty —
 * a scenario a mocked-DAO unit test can't catch, and which live-emulator verification of this
 * story caught as a real bug (an @Update-based save silently no-op'd on the missing row).
 */
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ris.imagedistributor.data.local.AppDatabase
import com.ris.imagedistributor.domain.AppResult
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RetentionRepositoryImplTest {

    private lateinit var database: AppDatabase
    private lateinit var repository: RetentionRepositoryImpl

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = RetentionRepositoryImpl(database.retentionSettingDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun getRetentionDays_returnsTheDefaultWhenNoRowExistsYet() = runTest {
        val result = repository.getRetentionDays()

        assertEquals(AppResult.Success(RetentionRepositoryImpl.DEFAULT_RETENTION_DAYS), result)
    }

    @Test
    fun setRetentionDays_actuallyPersistsEvenWhenNoRowExistedBefore() = runTest {
        // This is exactly the fresh-install scenario: no MIGRATION_6_7 has ever run against this
        // database, so `retention_settings` starts genuinely empty.
        val saveResult = repository.setRetentionDays(60)
        assertEquals(AppResult.Success(Unit), saveResult)

        val readResult = repository.getRetentionDays()
        assertEquals(AppResult.Success(60), readResult)
    }

    @Test
    fun setRetentionDays_overwritesAnExistingRowRatherThanFailingOrDuplicating() = runTest {
        repository.setRetentionDays(60)

        repository.setRetentionDays(90)

        val readResult = repository.getRetentionDays()
        assertEquals(AppResult.Success(90), readResult)
    }
}
