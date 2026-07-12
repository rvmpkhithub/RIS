package com.ris.imagedistributor.worker

/**
 * INSTRUMENTED — smoke test for the real CoroutineWorker/WorkManager wiring, using the real
 * [com.ris.imagedistributor.di.AppWorkerFactory] backed by a mocked [AppContainer], mirroring
 * [SendWorkerTest]'s exact rationale: a real AppContainer would open the same persisted Room
 * database the installed app uses. The CAP-8 business logic itself is not this test's job
 * ([com.ris.imagedistributor.domain.RetentionPolicyTest] already covers that with pure mocks) —
 * this just confirms the framework wiring lets RetentionPurgeWorker construct via the real
 * factory and run to completion.
 */
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.ris.imagedistributor.di.AppContainer
import com.ris.imagedistributor.di.AppWorkerFactory
import com.ris.imagedistributor.domain.AppResult
import com.ris.imagedistributor.domain.FailureReason
import com.ris.imagedistributor.domain.RetentionPolicy
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RetentionPurgeWorkerTest {

    private fun workerWith(retentionPolicy: RetentionPolicy): RetentionPurgeWorker {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val container: AppContainer = mockk()
        every { container.retentionPolicy } returns retentionPolicy
        return TestListenableWorkerBuilder.from(context, RetentionPurgeWorker::class.java)
            .setWorkerFactory(AppWorkerFactory(container))
            .build()
    }

    @Test
    fun retentionPurgeWorker_runsToCompletionViaTheRealAppWorkerFactory() = runTest {
        val retentionPolicy: RetentionPolicy = mockk()
        coEvery { retentionPolicy.purgeExpired() } returns AppResult.Success(0)

        val result = workerWith(retentionPolicy).doWork()

        assertTrue("expected Result.success(), got $result", result is ListenableWorker.Result.Success)
    }

    // [Review][Patch] regression test for the swallowed-AppResult.Failure bug — doWork() used to
    // always return Result.success() unless an exception escaped, even when purgeExpired()
    // reported a real DB failure via AppResult.
    @Test
    fun retentionPurgeWorker_retriesWhenPurgeExpiredReportsAFailure() = runTest {
        val retentionPolicy: RetentionPolicy = mockk()
        coEvery { retentionPolicy.purgeExpired() } returns AppResult.Failure(FailureReason.DATABASE_ERROR)

        val result = workerWith(retentionPolicy).doWork()

        assertTrue("expected Result.retry(), got $result", result is ListenableWorker.Result.Retry)
    }
}
