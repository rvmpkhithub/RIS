package com.ris.imagedistributor.data.repository

import android.net.Uri
import com.ris.imagedistributor.data.local.Image
import com.ris.imagedistributor.data.local.ImageDao
import com.ris.imagedistributor.data.local.ImageFileStore
import com.ris.imagedistributor.domain.AppResult
import com.ris.imagedistributor.domain.FailureReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import java.io.IOException
import java.time.Instant

class ImageRepositoryImpl(
    private val dao: ImageDao,
    private val fileStore: ImageFileStore,
) : ImageRepository {

    override fun observeImages(): Flow<List<Image>> =
        dao.observeAll().catch { e -> if (e is CancellationException) throw e else emit(emptyList()) }

    override suspend fun uploadImages(uris: List<Uri>): AppResult<Unit> {
        // Copy-then-insert per image — never insert a DB row before its file copy succeeds,
        // and never batch in a way that could leave a dangling reference. [Story 1.1 lesson]
        //
        // [Review][Patch] each uri is now attempted independently: the previous version wrapped
        // the whole forEach in one try/catch, so a failure on image N silently abandoned images
        // N+1..end (never attempted) while still keeping whatever 1..N-1 had already persisted —
        // yet reported the entire call as one blanket Failure. Also distinguishes a file-copy
        // failure (FILE_ERROR) from a genuine DB failure (DATABASE_ERROR), which the previous
        // single catch-all conflated.
        var failureReason: FailureReason? = null
        for (uri in uris) {
            var copiedFilename: String? = null
            try {
                copiedFilename = fileStore.copyToAppStorage(uri)
                dao.insert(Image(filePath = copiedFilename, active = true, uploadedAt = Instant.now().toEpochMilli()))
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                failureReason = failureReason ?: FailureReason.FILE_ERROR
            } catch (e: Exception) {
                // The copy succeeded but the DB insert then failed — don't leave an orphaned file
                // with no row ever referencing it.
                copiedFilename?.let { fileStore.delete(it) }
                failureReason = failureReason ?: FailureReason.DATABASE_ERROR
            }
        }
        return failureReason?.let { AppResult.Failure(it) } ?: AppResult.Success(Unit)
    }

    override suspend fun setActive(id: Long, active: Boolean): AppResult<Unit> =
        runCatchingDb { dao.setActive(id, active) }

    override suspend fun getActiveImages(): AppResult<List<Image>> =
        runCatchingDb { dao.getActive() }

    override suspend fun getImageById(id: Long): AppResult<Image?> =
        runCatchingDb { dao.getById(id) }

    override fun resolveFile(filePath: String) = fileStore.absolutePath(filePath)

    private suspend fun <T> runCatchingDb(block: suspend () -> T): AppResult<T> =
        try {
            AppResult.Success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppResult.Failure(FailureReason.DATABASE_ERROR)
        }
}
