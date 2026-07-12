package com.ris.imagedistributor.data.repository

import app.cash.turbine.test
import com.ris.imagedistributor.data.local.RetentionSetting
import com.ris.imagedistributor.data.local.RetentionSettingDao
import com.ris.imagedistributor.domain.AppResult
import com.ris.imagedistributor.domain.FailureReason
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class RetentionRepositoryImplTest {

    private lateinit var dao: RetentionSettingDao
    private lateinit var repository: RetentionRepositoryImpl

    @Before
    fun setUp() {
        dao = mockk()
        repository = RetentionRepositoryImpl(dao)
    }

    @Test
    fun `observeRetentionDays reflects the dao's emitted retentionDays`() = runTest {
        every { dao.observe() } returns flowOf(RetentionSetting(retentionDays = 45))

        repository.observeRetentionDays().test {
            assertEquals(45, awaitItem())
            awaitComplete()
        }
    }

    @Test
    fun `observeRetentionDays falls back to the default when the row is null`() = runTest {
        every { dao.observe() } returns flowOf(null)

        repository.observeRetentionDays().test {
            assertEquals(RetentionRepositoryImpl.DEFAULT_RETENTION_DAYS, awaitItem())
            awaitComplete()
        }
    }

    @Test
    fun `observeRetentionDays falls back to the default on a DAO error instead of throwing`() = runTest {
        every { dao.observe() } returns flow { throw RuntimeException("boom") }

        repository.observeRetentionDays().test {
            assertEquals(RetentionRepositoryImpl.DEFAULT_RETENTION_DAYS, awaitItem())
            awaitComplete()
        }
    }

    @Test
    fun `observeRetentionDays does not swallow CancellationException`() = runTest {
        every { dao.observe() } returns flow { throw CancellationException("cancelled") }

        try {
            repository.observeRetentionDays().collect { }
            fail("expected CancellationException to propagate")
        } catch (e: CancellationException) {
            // expected
        }
    }

    @Test
    fun `getRetentionDays returns the dao's value on success`() = runTest {
        coEvery { dao.getOnce() } returns RetentionSetting(retentionDays = 60)

        val result = repository.getRetentionDays()

        assertEquals(AppResult.Success(60), result)
    }

    @Test
    fun `getRetentionDays falls back to the default when the row is null`() = runTest {
        coEvery { dao.getOnce() } returns null

        val result = repository.getRetentionDays()

        assertEquals(AppResult.Success(RetentionRepositoryImpl.DEFAULT_RETENTION_DAYS), result)
    }

    @Test
    fun `getRetentionDays DB failure maps to DATABASE_ERROR`() = runTest {
        coEvery { dao.getOnce() } throws RuntimeException("boom")

        val result = repository.getRetentionDays()

        assertEquals(AppResult.Failure(FailureReason.DATABASE_ERROR), result)
    }

    @Test
    fun `setRetentionDays upserts the singleton row with the given value`() = runTest {
        coEvery { dao.upsert(RetentionSetting(retentionDays = 90)) } returns Unit

        val result = repository.setRetentionDays(90)

        assertEquals(AppResult.Success(Unit), result)
        coVerify { dao.upsert(RetentionSetting(retentionDays = 90)) }
    }

    @Test
    fun `setRetentionDays DB failure maps to DATABASE_ERROR`() = runTest {
        coEvery { dao.upsert(any()) } throws RuntimeException("boom")

        val result = repository.setRetentionDays(90)

        assertEquals(AppResult.Failure(FailureReason.DATABASE_ERROR), result)
    }

    @Test
    fun `setRetentionDays rejects zero or negative values without touching the dao`() = runTest {
        val zeroResult = repository.setRetentionDays(0)
        val negativeResult = repository.setRetentionDays(-5)

        assertEquals(AppResult.Failure(FailureReason.INVALID_INPUT), zeroResult)
        assertEquals(AppResult.Failure(FailureReason.INVALID_INPUT), negativeResult)
        coVerify(exactly = 0) { dao.upsert(any()) }
    }
}
