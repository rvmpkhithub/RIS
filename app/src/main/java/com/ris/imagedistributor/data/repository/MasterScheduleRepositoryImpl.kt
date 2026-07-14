package com.ris.imagedistributor.data.repository

import androidx.room.withTransaction
import com.ris.imagedistributor.data.local.AppDatabase
import com.ris.imagedistributor.data.local.MasterSchedule
import com.ris.imagedistributor.domain.AppResult
import com.ris.imagedistributor.domain.FailureReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

class MasterScheduleRepositoryImpl(private val database: AppDatabase) : MasterScheduleRepository {

    private val dao = database.masterScheduleDao()

    override fun observeScheduleTimes(): Flow<List<Int>> =
        dao.observeAll()
            // A fresh install's table is genuinely empty (no exception, no rows) until the first
            // save — ifEmpty covers that, catch covers an actual DB read failure.
            .map { list -> list.map { it.time }.ifEmpty { DEFAULT_MASTER_SCHEDULE_TIMES } }
            .catch { e -> if (e is CancellationException) throw e else emit(DEFAULT_MASTER_SCHEDULE_TIMES) }

    override suspend fun getScheduleTimes(): AppResult<List<Int>> =
        runCatchingDb { dao.getAllOnce().map { it.time }.ifEmpty { DEFAULT_MASTER_SCHEDULE_TIMES } }

    override suspend fun setScheduleTimes(times: List<Int>): AppResult<Unit> {
        // Unlike a receiver's own schedule, zero is never valid here — there is nothing for the
        // master schedule itself to fall back to. [ARCHITECTURE-SPINE.md#AD-16, Consistency Conventions]
        if (times.size < MIN_SCHEDULE_TIMES) return AppResult.Failure(FailureReason.INVALID_INPUT)
        return runCatchingDb {
            database.withTransaction {
                dao.deleteAll()
                times.forEach { time -> dao.insert(MasterSchedule(time = time)) }
            }
        }
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
        /** Same minimum as a receiver's own schedule (ui.receivers.MIN_SCHEDULE_TIMES) — duplicated
         * rather than imported, since a data-layer class must not depend on a ui-layer constant. */
        const val MIN_SCHEDULE_TIMES = 4

        /** Same 4 default times seeded by AppDatabase.MIGRATION_7_8 — keep these two in sync. */
        val DEFAULT_MASTER_SCHEDULE_TIMES = listOf(540, 720, 900, 1080)
    }
}
