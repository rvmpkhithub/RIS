package com.ris.imagedistributor.ui

import androidx.compose.runtime.saveable.Saver
import com.ris.imagedistributor.data.repository.ComplianceRepository
import com.ris.imagedistributor.domain.AppResult
import com.ris.imagedistributor.domain.ComplianceGate
import com.ris.imagedistributor.domain.GateResult

sealed class AppRoute {
    data object Loading : AppRoute()
    data object Setup : AppRoute()
    data object MainApp : AppRoute()
    data object ComplianceHalt : AppRoute()
}

/** Plain, no-Compose-dependency saver — lets AppRoute survive rotation via rememberSaveable. */
val AppRouteSaver: Saver<AppRoute, String> = Saver(
    save = { route ->
        when (route) {
            AppRoute.Loading -> "loading"
            AppRoute.Setup -> "setup"
            AppRoute.MainApp -> "main"
            AppRoute.ComplianceHalt -> "halt"
        }
    },
    restore = { key ->
        when (key) {
            "setup" -> AppRoute.Setup
            "main" -> AppRoute.MainApp
            "halt" -> AppRoute.ComplianceHalt
            else -> AppRoute.Loading
        }
    },
)

/**
 * Launch routing decision (Story 1.1 Task 8), extracted as a plain suspend function so it's
 * unit-testable without Compose test infra — closes the "no test coverage for routing logic"
 * review finding.
 *
 * No row / locked == false -> Setup. locked == true -> re-run ComplianceGate.evaluate() before
 * proceeding. A getState() DB read failure falls back to Setup — the safest self-correcting
 * choice: worst case the operator re-enters already-known info, which lockRegistration's
 * already-locked no-op then absorbs harmlessly; the alternative (guessing MainApp/Halt) risks
 * bypassing or wrongly triggering the compliance gate.
 */
suspend fun determineRoute(repository: ComplianceRepository, complianceGate: ComplianceGate): AppRoute {
    val state = (repository.getState() as? AppResult.Success)?.value ?: return AppRoute.Setup
    if (!state.locked) return AppRoute.Setup

    return when (complianceGate.evaluate(state.nickname, state.city)) {
        GateResult.Proceed -> AppRoute.MainApp
        GateResult.Halt -> AppRoute.ComplianceHalt
    }
}
