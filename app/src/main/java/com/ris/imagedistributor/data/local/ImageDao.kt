package com.ris.imagedistributor.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ImageDao {
    @Query("SELECT * FROM images ORDER BY uploadedAt DESC")
    fun observeAll(): Flow<List<Image>>

    @Insert
    suspend fun insert(image: Image): Long

    @Query("UPDATE images SET active = :active WHERE id = :id")
    suspend fun setActive(id: Long, active: Boolean)

    /** One-shot snapshot for ImageSelectionEngine — distinct from observeAll()'s live Flow. */
    @Query("SELECT * FROM images WHERE active = 1")
    suspend fun getActive(): List<Image>

    /**
     * For SendWorker (Story 2.2) retrying/delivering a previously-queued image regardless of its
     * current `active` flag — mechanics.md: an image already queued is still sent even if flagged
     * inactive before the queue fires; `getActive()` would incorrectly drop exactly that case.
     */
    @Query("SELECT * FROM images WHERE id = :id")
    suspend fun getById(id: Long): Image?

    @Query("UPDATE images SET title = :title, description = :description WHERE id = :id")
    suspend fun updateDetails(id: Long, title: String?, description: String?)
}
