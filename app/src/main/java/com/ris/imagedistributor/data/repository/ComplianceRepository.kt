package com.ris.imagedistributor.data.repository

import com.ris.imagedistributor.data.local.ComplianceState
import com.ris.imagedistributor.domain.AppResult
import kotlinx.coroutines.flow.Flow

/**
 * The only class that constructs/calls RegistrationApi and ComplianceApi. [AD-2]
 * Every method returns AppResult<T> — IOException/HttpException/Room exceptions are caught and
 * translated here; nothing above this layer ever sees a raw exception. [AD-8]
 */
interface ComplianceRepository {
    /** Flow-based; degrades to emitting null on a read error rather than throwing. [AD-8] */
    fun observeState(): Flow<ComplianceState?>

    suspend fun getState(): AppResult<ComplianceState?>

    /**
     * Persists the permanent lock immediately — independent of whether registration or the
     * compliance check succeed. This is what makes CAP-6's "locked with no edit path afterward"
     * true even if the live compliance check that follows fails/times out.
     * No-op (returns the existing state) if a locked row already exists — see [Review][Patch]
     * "lockRegistration has no guard against being called a second time".
     */
    suspend fun lockRegistration(nickname: String, city: String): AppResult<Unit>

    suspend fun register(nickname: String, city: String): AppResult<Unit>
    suspend fun checkCompliance(nickname: String, city: String): AppResult<Boolean>
    suspend fun recordCheckResult(isCompliant: Boolean, checkedAt: Long): AppResult<Unit>
}
