package com.ris.imagedistributor.data.repository

import androidx.room.withTransaction
import com.ris.imagedistributor.data.local.AppDatabase
import com.ris.imagedistributor.data.local.Receiver
import com.ris.imagedistributor.data.local.ReceiverSchedule
import com.ris.imagedistributor.data.local.ReceiverWithSchedules
import com.ris.imagedistributor.domain.AppResult
import com.ris.imagedistributor.domain.FailureReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

class ReceiverRepositoryImpl(private val database: AppDatabase) : ReceiverRepository {

    private val receiverDao = database.receiverDao()
    private val scheduleDao = database.receiverScheduleDao()

    override fun observeReceivers(): Flow<List<ReceiverWithSchedules>> =
        receiverDao.observeAllWithSchedules()
            .map { list -> list.map { it.toDomain() } }
            .catch { e -> if (e is CancellationException) throw e else emit(emptyList()) }

    override suspend fun getAllWithSchedules(): AppResult<List<ReceiverWithSchedules>> =
        runCatchingDb { receiverDao.getAllWithSchedules().map { it.toDomain() } }

    override suspend fun addReceiver(receiver: Receiver, scheduleTimes: List<Int>): AppResult<Unit> =
        runCatchingDb {
            database.withTransaction {
                val id = receiverDao.insert(receiver)
                scheduleTimes.forEach { time -> scheduleDao.insert(ReceiverSchedule(receiverId = id, time = time)) }
            }
        }

    override suspend fun updateReceiver(receiver: Receiver, scheduleTimes: List<Int>): AppResult<Unit> =
        runCatchingDb {
            database.withTransaction {
                receiverDao.update(receiver)
                // Replace the full schedule set rather than diffing — simple, correct, and this
                // form is only ever saved as a whole (no partial schedule edits from the UI).
                scheduleDao.deleteAllForReceiver(receiver.id)
                scheduleTimes.forEach { time -> scheduleDao.insert(ReceiverSchedule(receiverId = receiver.id, time = time)) }
            }
        }

    override suspend fun deleteReceiver(id: Long): AppResult<Unit> =
        runCatchingDb {
            database.withTransaction {
                scheduleDao.deleteAllForReceiver(id)
                receiverDao.deleteById(id)
            }
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
