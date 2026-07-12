package com.ris.imagedistributor.ui

import android.app.Application
import androidx.work.Configuration
import com.ris.imagedistributor.di.AppContainer
import com.ris.imagedistributor.di.AppWorkerFactory
import com.ris.imagedistributor.worker.RetentionPurgeWorker
import com.ris.imagedistributor.worker.SendWorker

/**
 * Owns the single AppContainer instance for the process — fixes the review finding that
 * AppContainer (and its Room DB / Retrofit clients) was being reconstructed on every Activity
 * recreation when it lived in MainActivity.onCreate.
 *
 * Implements Configuration.Provider so WorkManager uses [AppWorkerFactory] (manual DI, no
 * framework) instead of the default zero-arg factory — SendWorker needs a constructor-injected
 * SendDispatcher. WorkManager's default manifest auto-initialization is suppressed
 * (AndroidManifest.xml) so this custom configuration is actually used instead of silently
 * fighting the default one.
 */
class ImageDropApplication : Application(), Configuration.Provider {
    val container: AppContainer by lazy { AppContainer(this) }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(AppWorkerFactory(container))
            .build()

    override fun onCreate() {
        super.onCreate()
        SendWorker.enqueuePeriodic(this)
        RetentionPurgeWorker.enqueuePeriodic(this)
    }
}
