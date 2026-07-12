package com.ris.imagedistributor.domain

import com.ris.imagedistributor.data.repository.RetentionRepository
import com.ris.imagedistributor.data.repository.TransmissionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * CAP-8 retention/purge rule — the only place the age-cutoff math is computed. [AD-10]
 *
 * Same constructor-injected-repositories-plus-injectable-clock shape as [ComplianceGate]/
 * [ImageSelectionEngine]. Workers and ViewModels call this; they never reimplement the logic
 * inline — [com.ris.imagedistributor.ui.dashboard.DashboardViewModel] uses [observeCutoff]
 * rather than reading [RetentionRepository.observeRetentionDays] and subtracting the days
 * itself. [Review][Patch]: an earlier version of `DashboardViewModel` did exactly that
 * duplication before this method existed.
 */
class RetentionPolicy(
    private val retentionRepository: RetentionRepository,
    private val transmissionRepository: TransmissionRepository,
    private val now: () -> Instant = Instant::now,
) {
    suspend fun purgeExpired(): AppResult<Int> {
        val retentionDays = when (val result = retentionRepository.getRetentionDays()) {
            is AppResult.Success -> result.value
            is AppResult.Failure -> return result
        }
        return transmissionRepository.purgeOlderThan(cutoffFor(retentionDays))
    }

    /** Live cutoff Instant for reactive read consumers (e.g. the Dashboard's history window). */
    fun observeCutoff(): Flow<Instant> =
        retentionRepository.observeRetentionDays().map { cutoffFor(it) }

    private fun cutoffFor(retentionDays: Int): Instant = now().minus(retentionDays.toLong(), ChronoUnit.DAYS)
}
