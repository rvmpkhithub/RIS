package com.ris.imagedistributor.data.repository

import com.ris.imagedistributor.data.local.ComplianceState
import com.ris.imagedistributor.data.local.ComplianceStateDao
import com.ris.imagedistributor.data.remote.ComplianceApi
import com.ris.imagedistributor.data.remote.ComplianceCheckRequest
import com.ris.imagedistributor.data.remote.ComplianceCheckResponse
import com.ris.imagedistributor.data.remote.RegistrationApi
import com.ris.imagedistributor.data.remote.RegistrationRequest
import com.ris.imagedistributor.domain.AppResult
import com.ris.imagedistributor.domain.FailureReason
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

class ComplianceRepositoryImplTest {

    private lateinit var dao: ComplianceStateDao
    private lateinit var registrationApi: RegistrationApi
    private lateinit var complianceApi: ComplianceApi
    private lateinit var repository: ComplianceRepositoryImpl

    @Before
    fun setUp() {
        dao = mockk(relaxed = true)
        registrationApi = mockk()
        complianceApi = mockk()
        repository = ComplianceRepositoryImpl(dao, registrationApi, complianceApi)
    }

    @Test
    fun `lockRegistration persists a locked, compliant-by-default row when none exists`() = runTest {
        coEvery { dao.getOnce() } returns null

        val result = repository.lockRegistration("Arjun", "Pune")

        assertTrue(result is AppResult.Success)
        coVerify {
            dao.upsert(
                ComplianceState(nickname = "Arjun", city = "Pune", locked = true, isCompliant = true, lastCheckedAt = null)
            )
        }
    }

    @Test
    fun `lockRegistration is a no-op when a locked row already exists`() = runTest {
        val existing = ComplianceState(nickname = "Arjun", city = "Pune", locked = true, isCompliant = false, lastCheckedAt = 5000L)
        coEvery { dao.getOnce() } returns existing

        val result = repository.lockRegistration("Someone", "Else")

        assertTrue(result is AppResult.Success)
        coVerify(exactly = 0) { dao.upsert(any()) }
    }

    @Test
    fun `lockRegistration DB failure maps to DATABASE_ERROR`() = runTest {
        coEvery { dao.getOnce() } throws RuntimeException("disk full")

        val result = repository.lockRegistration("Arjun", "Pune")

        assertEquals(AppResult.Failure(FailureReason.DATABASE_ERROR), result)
    }

    @Test
    fun `register success wraps in AppResult Success`() = runTest {
        coEvery { registrationApi.register(RegistrationRequest("Arjun", "Pune")) } returns Unit

        val result = repository.register("Arjun", "Pune")

        assertTrue(result is AppResult.Success)
    }

    @Test
    fun `register network failure maps to NETWORK_UNREACHABLE`() = runTest {
        coEvery { registrationApi.register(any()) } throws IOException("no network")

        val result = repository.register("Arjun", "Pune")

        assertEquals(AppResult.Failure(FailureReason.NETWORK_UNREACHABLE), result)
    }

    @Test(expected = CancellationException::class)
    fun `register does not swallow CancellationException`() = runTest {
        coEvery { registrationApi.register(any()) } throws CancellationException("cancelled")

        repository.register("Arjun", "Pune")
    }

    @Test
    fun `checkCompliance http error maps to SERVER_ERROR, never treated as non-compliant`() = runTest {
        val httpException = HttpException(Response.error<Any>(500, "".toResponseBody("text/plain".toMediaType())))
        coEvery { complianceApi.checkCompliance(any()) } throws httpException

        val result = repository.checkCompliance("Arjun", "Pune")

        assertEquals(AppResult.Failure(FailureReason.SERVER_ERROR), result)
    }

    @Test
    fun `checkCompliance explicit compliant false is preserved as Success`() = runTest {
        coEvery { complianceApi.checkCompliance(ComplianceCheckRequest("Arjun", "Pune")) } returns
            ComplianceCheckResponse(compliant = false)

        val result = repository.checkCompliance("Arjun", "Pune")

        assertEquals(AppResult.Success(false), result)
    }

    @Test
    fun `recordCheckResult updates the existing row without touching nickname city or locked`() = runTest {
        val existing = ComplianceState(nickname = "Arjun", city = "Pune", locked = true, isCompliant = true, lastCheckedAt = null)
        coEvery { dao.getOnce() } returns existing

        val result = repository.recordCheckResult(isCompliant = false, checkedAt = 1000L)

        assertTrue(result is AppResult.Success)
        coVerify {
            dao.upsert(existing.copy(isCompliant = false, lastCheckedAt = 1000L))
        }
    }

    @Test
    fun `recordCheckResult is a no-op when no row exists yet`() = runTest {
        coEvery { dao.getOnce() } returns null

        repository.recordCheckResult(isCompliant = false, checkedAt = 1000L)

        coVerify(exactly = 0) { dao.upsert(any()) }
    }

    @Test
    fun `getState DB failure maps to DATABASE_ERROR`() = runTest {
        coEvery { dao.getOnce() } throws RuntimeException("disk full")

        val result = repository.getState()

        assertEquals(AppResult.Failure(FailureReason.DATABASE_ERROR), result)
    }
}
