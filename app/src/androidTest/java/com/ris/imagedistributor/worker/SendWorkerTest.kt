package com.ris.imagedistributor.worker

/**
 * INSTRUMENTED — smoke test for the real CoroutineWorker/WorkManager wiring, using the real
 * [com.ris.imagedistributor.di.AppWorkerFactory] (not an inline ad-hoc factory) backed by a
 * mocked [AppContainer] — a *real* AppContainer would open the same persisted Room database file
 * the installed app itself uses (`AppDatabase.DATABASE_NAME` is not in-memory), which would make
 * this test both unrepeatable and liable to attempt real network calls against whatever receivers
 * happen to exist on the test device. Mocking `container.sendDispatcher` avoids that while still
 * exercising the actual production `AppWorkerFactory` routing logic this story introduced.
 * The CAP-4 business logic itself is not this test's job (SendDispatcherTest already covers that
 * exhaustively with pure mocks) — this just confirms the framework wiring lets SendWorker
 * construct via the real factory and run to completion.
 */
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.ris.imagedistributor.data.repository.ComplianceRepository
import com.ris.imagedistributor.data.repository.DeliveryRepository
import com.ris.imagedistributor.data.repository.ImageRepository
import com.ris.imagedistributor.data.repository.MasterScheduleRepository
import com.ris.imagedistributor.data.repository.ReceiverRepository
import com.ris.imagedistributor.data.repository.TransmissionRepository
import com.ris.imagedistributor.di.AppContainer
import com.ris.imagedistributor.di.AppWorkerFactory
import com.ris.imagedistributor.domain.AppResult
import com.ris.imagedistributor.domain.ComplianceGate
import com.ris.imagedistributor.domain.ImageSelectionEngine
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SendWorkerTest {

    @Test
    fun sendWorker_runsToCompletionViaTheRealAppWorkerFactory() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()

        val receiverRepository: ReceiverRepository = mockk()
        coEvery { receiverRepository.getAllWithSchedules() } returns AppResult.Success(emptyList())
        val imageRepository: ImageRepository = mockk()
        val transmissionRepository: TransmissionRepository = mockk()
        coEvery { transmissionRepository.getRetryCandidates() } returns AppResult.Success(emptyList())
        val deliveryRepository: DeliveryRepository = mockk()
        val complianceRepository: ComplianceRepository = mockk()
        // No compliance state -> SendDispatcher no-ops cleanly without needing a real ComplianceGate.
        coEvery { complianceRepository.getState() } returns AppResult.Success(null)
        val complianceGate: ComplianceGate = mockk()
        val imageSelectionEngine = ImageSelectionEngine(imageRepository)
        val masterScheduleRepository: MasterScheduleRepository = mockk()

        val dispatcher = SendDispatcher(
            receiverRepository, imageRepository, imageSelectionEngine, transmissionRepository,
            deliveryRepository, complianceRepository, complianceGate, masterScheduleRepository,
        )
        val container: AppContainer = mockk()
        every { container.sendDispatcher } returns dispatcher

        val worker = TestListenableWorkerBuilder.from(context, SendWorker::class.java)
            .setWorkerFactory(AppWorkerFactory(container))
            .build()

        val result = worker.doWork()

        assertTrue("expected Result.success(), got $result", result is ListenableWorker.Result.Success)
    }
}
