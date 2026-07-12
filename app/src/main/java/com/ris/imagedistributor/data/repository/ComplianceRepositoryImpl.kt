package com.ris.imagedistributor.data.repository

import com.ris.imagedistributor.data.local.ComplianceState
import com.ris.imagedistributor.data.local.ComplianceStateDao
import com.ris.imagedistributor.data.remote.ComplianceApi
import com.ris.imagedistributor.data.remote.ComplianceCheckRequest
import com.ris.imagedistributor.data.remote.RegistrationApi
import com.ris.imagedistributor.data.remote.RegistrationRequest
import com.ris.imagedistributor.domain.AppResult
import com.ris.imagedistributor.domain.FailureReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import retrofit2.HttpException
import java.io.IOException

class ComplianceRepositoryImpl(
    private val dao: ComplianceStateDao,
    private val registrationApi: RegistrationApi,
    private val complianceApi: ComplianceApi,
) : ComplianceRepository {

    override fun observeState(): Flow<ComplianceState?> =
        dao.observe().catch { e -> if (e is CancellationException) throw e else emit(null) }

    override suspend fun getState(): AppResult<ComplianceState?> =
        runCatchingDb { dao.getOnce() }

    override suspend fun lockRegistration(nickname: String, city: String): AppResult<Unit> =
        runCatchingDb {
            val existing = dao.getOnce()
            if (existing != null && existing.locked) return@runCatchingDb Unit // already locked — no-op, don't overwrite
            dao.upsert(
                ComplianceState(
                    nickname = nickname,
                    city = city,
                    locked = true,
                    isCompliant = true, // compliant by default until a live check says otherwise
                    lastCheckedAt = null,
                )
            )
        }

    override suspend fun register(nickname: String, city: String): AppResult<Unit> =
        runCatchingApi { registrationApi.register(RegistrationRequest(nickname, city)) }

    override suspend fun checkCompliance(nickname: String, city: String): AppResult<Boolean> =
        runCatchingApi { complianceApi.checkCompliance(ComplianceCheckRequest(nickname, city)).compliant }

    override suspend fun recordCheckResult(isCompliant: Boolean, checkedAt: Long): AppResult<Unit> =
        runCatchingDb {
            val current = dao.getOnce() ?: return@runCatchingDb Unit
            dao.upsert(current.copy(isCompliant = isCompliant, lastCheckedAt = checkedAt))
        }

    private suspend fun <T> runCatchingApi(block: suspend () -> T): AppResult<T> =
        try {
            AppResult.Success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            AppResult.Failure(FailureReason.NETWORK_UNREACHABLE)
        } catch (e: HttpException) {
            AppResult.Failure(FailureReason.SERVER_ERROR)
        } catch (e: Exception) {
            AppResult.Failure(FailureReason.UNKNOWN)
        }

    private suspend fun <T> runCatchingDb(block: suspend () -> T): AppResult<T> =
        try {
            AppResult.Success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppResult.Failure(FailureReason.DATABASE_ERROR)
        }
}
