package com.ris.imagedistributor.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/**
 * Thin CoroutineWorker adapter — all CAP-4 business logic lives in [SendDispatcher] so it can be
 * unit-tested without any CoroutineWorker/Context machinery. Constructed via [di.AppWorkerFactory],
 * not the default zero-arg factory (this class takes a [SendDispatcher] dependency).
 *
 * `Result.success()` even when individual queued items failed this run — those items stay
 * `PENDING`/become `FAILED` in the Transmission table and are picked up (or not) by the *next*
 * periodic tick, not by WorkManager's own retry/backoff. `Result.retry()` is reserved for the run
 * itself throwing (e.g. failing to even load receivers) — see [SendDispatchException]. Capped at
 * [MAX_WORKER_RETRIES] so a persistent bug/config error doesn't retry forever within one period.
 */
class SendWorker(
    context: Context,
    params: WorkerParameters,
    private val dispatcher: SendDispatcher,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result =
        try {
            dispatcher.dispatchDueSends()
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (runAttemptCount >= MAX_WORKER_RETRIES) Result.failure() else Result.retry()
        }

    companion object {
        private const val UNIQUE_WORK_NAME = "send-worker"
        private const val MAX_WORKER_RETRIES = 3

        /**
         * One periodic scanning Worker, every 15 minutes — AD-12; also WorkManager's own enforced
         * minimum periodic interval. Requires connectivity: with no network, every delivery
         * attempt is guaranteed to fail, so gating the whole run avoids burning the per-item
         * retry budget ([TransmissionRepository.MAX_SEND_ATTEMPTS]) while offline — this is what
         * actually makes the "offline-safe" queue resume once connectivity returns rather than
         * exhausting retries during the outage itself.
         */
        fun enqueuePeriodic(context: Context) {
            val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            val request = PeriodicWorkRequestBuilder<SendWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(UNIQUE_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
