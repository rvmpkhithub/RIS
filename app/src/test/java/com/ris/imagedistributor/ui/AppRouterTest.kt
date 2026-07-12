package com.ris.imagedistributor.ui

import com.ris.imagedistributor.data.local.ComplianceState
import com.ris.imagedistributor.data.repository.ComplianceRepository
import com.ris.imagedistributor.domain.AppResult
import com.ris.imagedistributor.domain.ComplianceGate
import com.ris.imagedistributor.domain.FailureReason
import com.ris.imagedistributor.domain.GateResult
import androidx.compose.runtime.saveable.SaverScope
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Closes the review finding that the actual launch-routing logic (the "no edit path after
 * first submit" invariant) was untested by anything that runs.
 */
class AppRouterTest {

    @Test
    fun `no row yet routes to Setup`() = runTest {
        val repository = mockk<ComplianceRepository>()
        val gate = mockk<ComplianceGate>()
        coEvery { repository.getState() } returns AppResult.Success(null)

        assertEquals(AppRoute.Setup, determineRoute(repository, gate))
    }

    @Test
    fun `unlocked row routes to Setup`() = runTest {
        val repository = mockk<ComplianceRepository>()
        val gate = mockk<ComplianceGate>()
        coEvery { repository.getState() } returns AppResult.Success(
            ComplianceState(nickname = "Arjun", city = "Pune", locked = false, isCompliant = true, lastCheckedAt = null)
        )

        assertEquals(AppRoute.Setup, determineRoute(repository, gate))
    }

    @Test
    fun `locked row that gates Proceed routes to MainApp`() = runTest {
        val repository = mockk<ComplianceRepository>()
        val gate = mockk<ComplianceGate>()
        coEvery { repository.getState() } returns AppResult.Success(
            ComplianceState(nickname = "Arjun", city = "Pune", locked = true, isCompliant = true, lastCheckedAt = 1000L)
        )
        coEvery { gate.evaluate("Arjun", "Pune") } returns GateResult.Proceed

        assertEquals(AppRoute.MainApp, determineRoute(repository, gate))
    }

    @Test
    fun `locked row that gates Halt routes to ComplianceHalt`() = runTest {
        val repository = mockk<ComplianceRepository>()
        val gate = mockk<ComplianceGate>()
        coEvery { repository.getState() } returns AppResult.Success(
            ComplianceState(nickname = "Arjun", city = "Pune", locked = true, isCompliant = true, lastCheckedAt = 1000L)
        )
        coEvery { gate.evaluate("Arjun", "Pune") } returns GateResult.Halt

        assertEquals(AppRoute.ComplianceHalt, determineRoute(repository, gate))
    }

    @Test
    fun `getState DB failure falls back to Setup rather than guessing MainApp or Halt`() = runTest {
        val repository = mockk<ComplianceRepository>()
        val gate = mockk<ComplianceGate>()
        coEvery { repository.getState() } returns AppResult.Failure(FailureReason.DATABASE_ERROR)

        assertEquals(AppRoute.Setup, determineRoute(repository, gate))
    }

    @Test
    fun `AppRouteSaver round-trips every route`() {
        val scope = SaverScope { true }
        listOf(AppRoute.Loading, AppRoute.Setup, AppRoute.MainApp, AppRoute.ComplianceHalt).forEach { route ->
            val saved = with(AppRouteSaver) { scope.save(route) }
            val restored = AppRouteSaver.restore(saved as String)
            assertEquals(route, restored)
        }
    }
}
