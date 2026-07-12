package com.ris.imagedistributor.data.repository

import com.ris.imagedistributor.data.local.DeliveryRecord
import com.ris.imagedistributor.data.local.Transmission
import com.ris.imagedistributor.data.local.TransmissionDao
import com.ris.imagedistributor.data.local.TransmissionStatus
import com.ris.imagedistributor.domain.AppResult
import com.ris.imagedistributor.domain.FailureReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.time.Instant

class TransmissionRepositoryImpl(private val dao: TransmissionDao) : TransmissionRepository {

    override suspend fun getRecentlySentImageIds(receiverId: Long, since: Instant): AppResult<List<Long>> =
        runCatchingDb {
            dao.getSentImageIdsSince(receiverId, TransmissionStatus.SENT.name, since.toEpochMilli())
        }

    override suspend fun enqueue(
        receiverId: Long,
        imageId: Long,
        scheduleTime: Int,
        queuedAt: Instant,
    ): AppResult<Transmission> =
        runCatchingDb {
            val transmission = Transmission(
                receiverId = receiverId,
                imageId = imageId,
                status = TransmissionStatus.PENDING.name,
                attemptCount = 0,
                sentAt = null,
                createdAt = queuedAt.toEpochMilli(),
                scheduleTime = scheduleTime,
            )
            val id = dao.insert(transmission)
            transmission.copy(id = id)
        }

    override suspend fun update(transmission: Transmission): AppResult<Unit> =
        runCatchingDb { dao.update(transmission) }

    override suspend fun getRetryCandidates(): AppResult<List<Transmission>> =
        runCatchingDb {
            dao.getRetryCandidates(TransmissionStatus.PENDING.name, TransmissionRepository.MAX_SEND_ATTEMPTS)
        }

    override suspend fun hasDispatchedToday(receiverId: Long, scheduleTime: Int, todayStart: Instant): AppResult<Boolean> =
        runCatchingDb {
            dao.countDispatchedSince(receiverId, scheduleTime, todayStart.toEpochMilli()) > 0
        }

    override fun observeSentHistory(receiverId: Long, since: Instant): Flow<List<DeliveryRecord>> =
        dao.observeSentHistory(receiverId, TransmissionStatus.SENT.name, since.toEpochMilli())
            .map { list -> list.mapNotNull { it.toDomain() } }
            .catch { e -> if (e is CancellationException) throw e else emit(emptyList()) }

    override suspend fun purgeOlderThan(cutoff: Instant): AppResult<Int> =
        runCatchingDb { dao.deleteOlderThan(cutoff.toEpochMilli()) }

    private suspend fun <T> runCatchingDb(block: suspend () -> T): AppResult<T> =
        try {
            AppResult.Success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppResult.Failure(FailureReason.DATABASE_ERROR)
        }
}
