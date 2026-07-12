package com.ris.imagedistributor.data.repository

import com.ris.imagedistributor.data.local.RetentionSetting
import com.ris.imagedistributor.data.local.RetentionSettingDao
import com.ris.imagedistributor.domain.AppResult
import com.ris.imagedistributor.domain.FailureReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

class RetentionRepositoryImpl(private val dao: RetentionSettingDao) : RetentionRepository {

    override fun observeRetentionDays(): Flow<Int> =
        dao.observe()
            .map { it?.retentionDays ?: DEFAULT_RETENTION_DAYS }
            .catch { e -> if (e is CancellationException) throw e else emit(DEFAULT_RETENTION_DAYS) }

    override suspend fun getRetentionDays(): AppResult<Int> =
        runCatchingDb { dao.getOnce()?.retentionDays ?: DEFAULT_RETENTION_DAYS }

    override suspend fun setRetentionDays(days: Int): AppResult<Unit> {
        // [Review][Patch] the Settings dialog already rejects <= 0 before ever calling this, but
        // that was the ONLY guard anywhere in the write path — any other caller (a future story,
        // a test, direct misuse) could persist a non-positive value, and RetentionPolicy would
        // then compute a cutoff at or after "now," purging most/all history on the next run.
        if (days <= 0) return AppResult.Failure(FailureReason.INVALID_INPUT)
        return runCatchingDb { dao.upsert(RetentionSetting(retentionDays = days)) }
    }

    private suspend fun <T> runCatchingDb(block: suspend () -> T): AppResult<T> =
        try {
            AppResult.Success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppResult.Failure(FailureReason.DATABASE_ERROR)
        }

    companion object {
        /** Defensive fallback only — MIGRATION_6_7 guarantees the id=1 row always exists. */
        const val DEFAULT_RETENTION_DAYS = 30
    }
}
