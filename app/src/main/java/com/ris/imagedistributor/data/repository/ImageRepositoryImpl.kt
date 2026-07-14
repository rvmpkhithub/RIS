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

    override suspend fun uploadImage(uri: Uri): AppResult<Long> {
        // Copy-then-insert — never insert a DB row before the file copy succeeds. [Story 1.1 lesson]
        // Distinguishes a file-copy failure (FILE_ERROR) from a genuine DB failure (DATABASE_ERROR).
        var copiedFilename: String? = null
        return try {
            copiedFilename = fileStore.copyToAppStorage(uri)
            val id = dao.insert(Image(filePath = copiedFilename, active = true, uploadedAt = Instant.now().toEpochMilli()))
            AppResult.Success(id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            AppResult.Failure(FailureReason.FILE_ERROR)
        } catch (e: Exception) {
            // The copy succeeded but the DB insert then failed — don't leave an orphaned file
            // with no row ever referencing it.
            copiedFilename?.let { fileStore.delete(it) }
            AppResult.Failure(FailureReason.DATABASE_ERROR)
        }
    }

    override suspend fun setActive(id: Long, active: Boolean): AppResult<Unit> =
        runCatchingDb { dao.setActive(id, active) }

    override suspend fun getActiveImages(): AppResult<List<Image>> =
        runCatchingDb { dao.getActive() }

    override suspend fun getImageById(id: Long): AppResult<Image?> =
        runCatchingDb { dao.getById(id) }

    override fun resolveFile(filePath: String) = fileStore.absolutePath(filePath)

    override suspend fun updateImageDetails(id: Long, title: String?, description: String?): AppResult<Unit> =
        runCatchingDb { dao.updateDetails(id, title?.trim()?.ifBlank { null }, description?.trim()?.ifBlank { null }) }

    private suspend fun <T> runCatchingDb(block: suspend () -> T): AppResult<T> =
        try {
            AppResult.Success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppResult.Failure(FailureReason.DATABASE_ERROR)
        }
}
