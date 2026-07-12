package com.ris.imagedistributor.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ris.imagedistributor.domain.AppResult
import com.ris.imagedistributor.domain.RetentionPolicy
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/**
 * Thin CoroutineWorker adapter — all CAP-8 business logic lives in [RetentionPolicy], mirroring
 * [SendWorker]'s exact shape. Constructed via [di.AppWorkerFactory].
 *
 * Runs daily, not every 15 minutes like [SendWorker] — purging is one-shot-per-day housekeeping,
 * not something that needs to react within minutes. No network constraint: purging is a pure
 * local-DB operation.
 */
class RetentionPurgeWorker(
    context: Context,
    params: WorkerParameters,
    private val retentionPolicy: RetentionPolicy,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result =
        try {
            // [Review][Patch] purgeExpired() returns AppResult rather than throwing on a DB
            // failure — the previous version discarded this and always returned success,
            // silently defeating the retry ceiling below for that entire failure class.
            when (retentionPolicy.purgeExpired()) {
                is AppResult.Success -> Result.success()
                is AppResult.Failure -> if (runAttemptCount >= MAX_WORKER_RETRIES) Result.failure() else Result.retry()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (runAttemptCount >= MAX_WORKER_RETRIES) Result.failure() else Result.retry()
        }

    companion object {
        private const val UNIQUE_WORK_NAME = "retention-purge-worker"
        private const val MAX_WORKER_RETRIES = 3
        private const val PURGE_INTERVAL_HOURS = 24L

        fun enqueuePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<RetentionPurgeWorker>(PURGE_INTERVAL_HOURS, TimeUnit.HOURS).build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(UNIQUE_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
