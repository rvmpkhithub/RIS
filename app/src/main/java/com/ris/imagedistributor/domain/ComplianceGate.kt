package com.ris.imagedistributor.domain

import com.ris.imagedistributor.data.repository.ComplianceRepository
import java.time.Instant

/**
 * CAP-7 fail-open rule, asymmetric cache use. [AD-10, AD-11 as amended 2026-07-09]
 *
 * Always performs a live compliance check first. A `Success` result gates directly on the live
 * answer. A `Failure` (unreachable/error) checks the cache: if the last *confirmed* live result
 * was explicitly non-compliant, the halt persists (an offline relaunch must not bypass a
 * confirmed halt); otherwise it fails open as before. The cache is never read to fabricate a
 * Proceed a live check didn't itself justify — only to extend an already-confirmed Halt.
 *
 * After every live evaluation, persists isCompliant/lastCheckedAt via the repository for
 * DISPLAY PURPOSES (and now also for the sticky-halt check above) — this method still never
 * uses the cache to invent a compliant verdict.
 *
 * Scope boundary: invoked directly from the app-launch/Setup flow (synchronous, foreground).
 * A separate ComplianceCheckWorker for periodic background re-checks belongs to Epic 2 — not
 * built here. [implementation-readiness-report-2026-07-09.md#Minor Concern 2]
 */
class ComplianceGate(private val repository: ComplianceRepository) {

    suspend fun evaluate(nickname: String, city: String): GateResult {
        return when (val result = repository.checkCompliance(nickname, city)) {
            is AppResult.Success -> {
                val compliant = result.value
                repository.recordCheckResult(isCompliant = compliant, checkedAt = Instant.now().toEpochMilli())
                if (compliant) GateResult.Proceed else GateResult.Halt
            }
            is AppResult.Failure -> {
                // Unreachable — check whether the last confirmed live result was a halt.
                // getState() failing too means "no confirmed information either way" -> fail open.
                when (val cached = repository.getState()) {
                    is AppResult.Success -> {
                        val state = cached.value
                        if (state != null && state.lastCheckedAt != null && !state.isCompliant) {
                            GateResult.Halt
                        } else {
                            GateResult.Proceed
                        }
                    }
                    is AppResult.Failure -> GateResult.Proceed
                }
            }
        }
    }
}

sealed class GateResult {
    data object Proceed : GateResult()
    data object Halt : GateResult()
}
