package com.ris.imagedistributor.domain

import app.cash.turbine.test
import com.ris.imagedistributor.data.repository.RetentionRepository
import com.ris.imagedistributor.data.repository.TransmissionRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.temporal.ChronoUnit

class RetentionPolicyTest {

    private lateinit var retentionRepository: RetentionRepository
    private lateinit var transmissionRepository: TransmissionRepository
    private lateinit var policy: RetentionPolicy
    private val fixedNow: Instant = Instant.ofEpochMilli(10_000_000_000L)

    @Before
    fun setUp() {
        retentionRepository = mockk()
        transmissionRepository = mockk()
        policy = RetentionPolicy(retentionRepository, transmissionRepository, now = { fixedNow })
    }

    @Test
    fun `purgeExpired computes a cutoff equal to now minus the configured retention days`() = runTest {
        coEvery { retentionRepository.getRetentionDays() } returns AppResult.Success(45)
        val cutoffSlot = slot<Instant>()
        coEvery { transmissionRepository.purgeOlderThan(capture(cutoffSlot)) } returns AppResult.Success(3)

        val result = policy.purgeExpired()

        assertEquals(AppResult.Success(3), result)
        assertEquals(fixedNow.minus(45, ChronoUnit.DAYS), cutoffSlot.captured)
    }

    @Test
    fun `purgeExpired propagates a getRetentionDays failure without calling purgeOlderThan`() = runTest {
        coEvery { retentionRepository.getRetentionDays() } returns AppResult.Failure(FailureReason.DATABASE_ERROR)

        val result = policy.purgeExpired()

        assertEquals(AppResult.Failure(FailureReason.DATABASE_ERROR), result)
        coVerify(exactly = 0) { transmissionRepository.purgeOlderThan(any()) }
    }

    @Test
    fun `purgeExpired propagates a purgeOlderThan failure unchanged`() = runTest {
        coEvery { retentionRepository.getRetentionDays() } returns AppResult.Success(30)
        coEvery { transmissionRepository.purgeOlderThan(any()) } returns AppResult.Failure(FailureReason.DATABASE_ERROR)

        val result = policy.purgeExpired()

        assertEquals(AppResult.Failure(FailureReason.DATABASE_ERROR), result)
    }

    // [Review][Patch] AD-10 fix — DashboardViewModel now reads this instead of computing
    // now() - retentionDays itself, so the cutoff formula lives in exactly one place.
    @Test
    fun `observeCutoff reflects now minus the live retention setting, recomputed whenever it changes`() = runTest {
        val retentionDaysFlow = MutableStateFlow(30)
        every { retentionRepository.observeRetentionDays() } returns retentionDaysFlow

        policy.observeCutoff().test {
            assertEquals(fixedNow.minus(30, ChronoUnit.DAYS), awaitItem())

            retentionDaysFlow.value = 60

            assertEquals(fixedNow.minus(60, ChronoUnit.DAYS), awaitItem())
        }
    }
}
