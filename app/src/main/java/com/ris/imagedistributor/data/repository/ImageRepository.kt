package com.ris.imagedistributor.data.repository

import android.net.Uri
import com.ris.imagedistributor.data.local.Image
import com.ris.imagedistributor.domain.AppResult
import kotlinx.coroutines.flow.Flow
import java.io.File

/**
 * Simple CRUD — no domain service layer. ViewModels call this directly per AD-5's carve-out
 * (business rules like ImageSelectionEngine, Epic 2, own the selection algorithm; this doesn't). [AD-5, AD-10]
 */
interface ImageRepository {
    fun observeImages(): Flow<List<Image>>

    /** Copies the picked image into app storage and inserts it, returning the new row's id. */
    suspend fun uploadImage(uri: Uri): AppResult<Long>
    suspend fun setActive(id: Long, active: Boolean): AppResult<Unit>

    /** One-shot snapshot of currently-active images, for ImageSelectionEngine (Story 2.1). */
    suspend fun getActiveImages(): AppResult<List<Image>>

    /** Fetches a specific image regardless of its `active` flag, for SendWorker (Story 2.2). */
    suspend fun getImageById(id: Long): AppResult<Image?>

    /** Resolves a stored relative filePath to a loadable File — the only path math the UI needs. */
    fun resolveFile(filePath: String): File

    /** Updates an image's title/description (Story 1.4) — blank strings are normalized to null. */
    suspend fun updateImageDetails(id: Long, title: String?, description: String?): AppResult<Unit>
}
