package com.ris.imagedistributor.di

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import com.ris.imagedistributor.worker.RetentionPurgeWorker
import com.ris.imagedistributor.worker.SendWorker

/** Manual DI for Workers — no framework. Falls through to the default factory for any Worker class not listed here. */
class AppWorkerFactory(private val container: AppContainer) : WorkerFactory() {

    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? =
        when (workerClassName) {
            SendWorker::class.java.name -> SendWorker(appContext, workerParameters, container.sendDispatcher)
            RetentionPurgeWorker::class.java.name -> RetentionPurgeWorker(appContext, workerParameters, container.retentionPolicy)
            else -> null
        }
}
